/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.sql.aisddl;

import com.akiban.ais.AISCloner;
import com.akiban.ais.model.AISBuilder;
import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.Column;
import com.akiban.ais.model.Columnar;
import com.akiban.ais.model.Group;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.IndexColumn;
import com.akiban.ais.model.Join;
import com.akiban.ais.model.JoinColumn;
import com.akiban.ais.model.Table;
import com.akiban.ais.model.TableIndex;
import com.akiban.ais.model.TableName;
import com.akiban.ais.model.UserTable;
import com.akiban.ais.protobuf.ProtobufWriter;
import com.akiban.ais.util.TableChange;
import com.akiban.server.api.DDLFunctions;
import com.akiban.server.api.DMLFunctions;
import com.akiban.server.error.JoinToMultipleParentsException;
import com.akiban.server.error.NoSuchColumnException;
import com.akiban.server.error.NoSuchGroupingFKException;
import com.akiban.server.error.NoSuchIndexException;
import com.akiban.server.error.NoSuchTableException;
import com.akiban.server.error.NoSuchUniqueException;
import com.akiban.server.error.UnsupportedCheckConstraintException;
import com.akiban.server.error.UnsupportedSQLException;
import com.akiban.server.service.session.Session;
import com.akiban.sql.parser.AlterTableNode;
import com.akiban.sql.parser.ColumnDefinitionNode;
import com.akiban.sql.parser.ConstraintDefinitionNode;
import com.akiban.sql.parser.FKConstraintDefinitionNode;
import com.akiban.sql.parser.ModifyColumnNode;
import com.akiban.sql.parser.NodeTypes;
import com.akiban.sql.parser.TableElementList;
import com.akiban.sql.parser.TableElementNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.akiban.ais.util.TableChange.ChangeType;
import static com.akiban.sql.aisddl.DDLHelper.convertName;
import static com.akiban.sql.parser.ConstraintDefinitionNode.ConstraintType;
import static com.akiban.util.Exceptions.throwAlways;

public class AlterTableDDL {
    private static final String GROUP_CHANGE_ERROR_MSG = "Cannot change column %s from %s (primary or grouping key)";

    private AlterTableDDL() {}

    public static void alterTable(DDLFunctions ddlFunctions,
                                  DMLFunctions dmlFunctions,
                                  Session session,
                                  String defaultSchemaName,
                                  AlterTableNode alterTable) {
        final AkibanInformationSchema curAIS = ddlFunctions.getAIS(session);
        final TableName tableName = convertName(defaultSchemaName, alterTable.getObjectName());
        final UserTable table = curAIS.getUserTable(tableName);
        checkExists(tableName, table);

        if (alterTable.isUpdateStatistics()) {
            Collection<String> indexes = null;
            if (!alterTable.isUpdateStatisticsAll())
                indexes = Collections.singletonList(alterTable.getIndexNameForUpdateStatistics());
            ddlFunctions.updateTableStatistics(session, tableName, indexes);
            return;
        }

        if (alterTable.isTruncateTable()) {
            dmlFunctions.truncateTable(session, table.getTableId());
            return;
        }

        if (processAlter(session, ddlFunctions, defaultSchemaName, table, alterTable.tableElementList)) {
            return;
        }

        throw new UnsupportedSQLException (alterTable.statementToString(), alterTable);
    }

    private static void checkExists(TableName tableName, UserTable table) {
        if (table == null) {
            throw new NoSuchTableException(tableName);
        }
    }

    private static boolean processAlter(Session session, DDLFunctions ddl, String defaultSchema, UserTable table, TableElementList elements) {
        // Should never come this way from the parser, but be defensive
        if((elements == null) || elements.isEmpty()) {
            return false;
        }

        List<TableChange> columnChanges = new ArrayList<TableChange>();
        List<TableChange> indexChanges = new ArrayList<TableChange>();
        List<ColumnDefinitionNode> columnDefNodes = new ArrayList<ColumnDefinitionNode>();
        List<FKConstraintDefinitionNode> fkDefNodes= new ArrayList<FKConstraintDefinitionNode>();
        List<ConstraintDefinitionNode> conDefNodes = new ArrayList<ConstraintDefinitionNode>();

        for(TableElementNode node : elements) {
            switch(node.getNodeType()) {
                case NodeTypes.COLUMN_DEFINITION_NODE: {
                    ColumnDefinitionNode cdn = (ColumnDefinitionNode) node;
                    columnChanges.add(TableChange.createAdd(cdn.getColumnName()));
                    columnDefNodes.add(cdn);
                } break;

                case NodeTypes.DROP_COLUMN_NODE: {
                    String columnName = ((ModifyColumnNode)node).getColumnName();
                    columnChanges.add(TableChange.createDrop(columnName));
                } break;

                case NodeTypes.MODIFY_COLUMN_TYPE_NODE: {
                    String columnName = ((ModifyColumnNode)node).getColumnName();
                    columnChanges.add(TableChange.createModify(columnName, columnName));
                    columnDefNodes.add((ColumnDefinitionNode) node);
                } break;

                case NodeTypes.FK_CONSTRAINT_DEFINITION_NODE: {
                    FKConstraintDefinitionNode fkNode = (FKConstraintDefinitionNode) node;
                    fkDefNodes.add(fkNode);
                } break;

                case NodeTypes.CONSTRAINT_DEFINITION_NODE: {
                    ConstraintDefinitionNode cdn = (ConstraintDefinitionNode) node;
                    if(cdn.getConstraintType() == ConstraintType.DROP) {
                        String name = cdn.getName();
                        switch(cdn.getVerifyType()) {
                            case PRIMARY_KEY:
                                name = Index.PRIMARY_KEY_CONSTRAINT;
                            break;
                            // TODO: Should add flags to AlterTableChange to avoid checks in multiple places
                            case DROP:
                            case UNIQUE:
                                Index index = table.getIndex(name);
                                if((index != null) && !index.isUnique()) {
                                    throw new NoSuchUniqueException(table.getName(), cdn.getName());
                                }
                            break;
                            case CHECK:
                                throw new UnsupportedCheckConstraintException();
                        }
                        indexChanges.add(TableChange.createDrop(name));
                    } else {
                        conDefNodes.add(cdn);
                    }
                } break;

                default:
                    return false; // Something unsupported
            }
        }

        final AkibanInformationSchema origAIS = table.getAIS();
        final UserTable tableCopy = copyTable(table, columnChanges, indexChanges);
        final AkibanInformationSchema aisCopy = tableCopy.getAIS();
        final AISBuilder builder = new AISBuilder(aisCopy);

        for(ColumnDefinitionNode cdn : columnDefNodes) {
            if(cdn instanceof ModifyColumnNode) {
                // TODO: Assumes SET DATA TYPE, expand as needed
                assert cdn.getNodeType() == NodeTypes.MODIFY_COLUMN_TYPE_NODE;
                Column column = tableCopy.dropColumn(cdn.getColumnName());
                int pos = column.getPosition();
                TableDDL.addColumn(builder, table.getName().getSchemaName(), table.getName().getTableName(),
                                   column.getName(), pos, cdn.getType(), column.getNullable(),
                                   column.getInitialAutoIncrementValue() != null);
            } else {
                int pos = table.getColumns().size();
                TableDDL.addColumn(builder, cdn, table.getName().getSchemaName(), table.getName().getTableName(), pos);
            }
        }

        TableName newName = tableCopy.getName();
        for(ConstraintDefinitionNode cdn : conDefNodes) {
            assert cdn.getConstraintType() != ConstraintType.DROP : cdn;
            String name = TableDDL.addIndex(builder, cdn, newName.getSchemaName(), newName.getTableName());
            indexChanges.add(TableChange.createAdd(name));
        }

        for(FKConstraintDefinitionNode fk : fkDefNodes) {
            if(fk.getConstraintType() == ConstraintType.DROP) {
                Join parentJoin = tableCopy.getParentJoin();
                if(parentJoin == null) {
                    throw new NoSuchGroupingFKException(table.getName());
                }
                tableCopy.setGroup(null);
                tableCopy.removeCandidateParentJoin(parentJoin);
                parentJoin.getParent().removeCandidateChildJoin(parentJoin);
            } else {
                if(table.getParentJoin() != null) {
                    throw new JoinToMultipleParentsException(table.getName());
                }
                TableName parent = TableDDL.getReferencedName(defaultSchema, fk);
                if((aisCopy.getUserTable(parent) == null) && (origAIS.getUserTable(parent) != null)) {
                    TableDDL.addParentTable(builder, origAIS, fk, defaultSchema);
                }
                tableCopy.setGroup(null);
                TableDDL.addJoin(builder, fk, defaultSchema, newName.getSchemaName(), newName.getTableName());
            }
        }

        tableCopy.endTable();

        ddl.alterTable(session, table.getName(), tableCopy, columnChanges, indexChanges);
        return true;
    }

    private static void checkColumnChange(UserTable table, String columnName) {
        Column column = table.getColumn(columnName);
        if(column == null) {
            throw new NoSuchColumnException(columnName);
        }
        // Reject until automatic grouping changes supported
        Join join = table.getParentJoin();
        if(join != null) {
            for(JoinColumn joinColumn : join.getJoinColumns()) {
                if(columnName.equals(joinColumn.getChild().getName())) {
                    throw new UnsupportedSQLException(String.format(GROUP_CHANGE_ERROR_MSG, columnName, table.getName()), null);
                }
            }
        }
    }

    private static void checkIndexChange(UserTable table, String indexName, boolean isNew) {
        Index index = table.getIndex(indexName);
        if(index == null && !isNew) {
            if(Index.PRIMARY_KEY_CONSTRAINT.equals(indexName)) {
                throw new NoSuchIndexException(indexName);
            } else {
                throw new NoSuchUniqueException(table.getName(), indexName);
            }
        }
    }

    private static ChangeType findOldName(List<TableChange> changes, String oldName) {
        for(TableChange change : changes) {
            if(oldName.equals(change.getOldName())) {
                return change.getChangeType();
            }
        }
        return null;
    }

    private static UserTable copyTable(UserTable origTable, List<TableChange> columnChanges, List<TableChange> indexChanges) {
        for(TableChange change : columnChanges) {
            if(change.getChangeType() != ChangeType.ADD) {
                checkColumnChange(origTable, change.getOldName());
            }
        }

        for(TableChange change : indexChanges) {
            checkIndexChange(origTable, change.getOldName(), change.getChangeType() == ChangeType.ADD);
        }

        AkibanInformationSchema aisCopy = AISCloner.clone(origTable.getAIS(), new GroupSelector(origTable.getGroup()));
        UserTable tableCopy = aisCopy.getUserTable(origTable.getName());

        // Remove all and recreate (note: hidden PK and column are handled by DDL interface)
        tableCopy.dropColumns();
        tableCopy.removeIndexes(tableCopy.getIndexesIncludingInternal());

        int colPos = 0;
        for(Column origColumn : origTable.getColumns()) {
            String columnName = origColumn.getName();
            if(findOldName(columnChanges, columnName) != ChangeType.DROP) {
                Column.create(tableCopy, origColumn, colPos++);
            }
        }

        Collection<TableIndex> indexesToDrop = new ArrayList<TableIndex>();
        for(TableIndex origIndex : origTable.getIndexes()) {
            ChangeType indexChange = findOldName(indexChanges, origIndex.getIndexName().getName());
            if(indexChange == ChangeType.DROP) {
                continue;
            }
            TableIndex indexCopy = TableIndex.create(tableCopy, origIndex);
            boolean didModify = false;
            int pos = 0;
            for(IndexColumn indexColumn : origIndex.getKeyColumns()) {
                ChangeType change = findOldName(columnChanges, indexColumn.getColumn().getName());
                if(change != ChangeType.DROP) {
                    didModify |= (change == ChangeType.MODIFY);
                    IndexColumn.create(indexCopy, tableCopy.getColumn(indexColumn.getColumn().getName()), indexColumn, pos++);
                } else {
                    didModify = true;
                }
            }

            // Automatically mark indexes for drop or modification
            if(indexCopy.getKeyColumns().isEmpty()) {
                indexesToDrop.add(indexCopy);
                indexChanges.add(TableChange.createDrop(indexCopy.getIndexName().getName()));
            } else if(didModify && (indexChange == null)) {
                String indexName = indexCopy.getIndexName().getName();
                indexChanges.add(TableChange.createModify(indexName, indexName));
            }
        }

        tableCopy.removeIndexes(indexesToDrop);
        return tableCopy;
    }

    private static class GroupSelector extends ProtobufWriter.TableSelector {
        private final Group group;

        public GroupSelector(Group group) {
            this.group = group;
        }

        @Override
        public boolean isSelected(Columnar columnar) {
            return columnar.isTable() && ((Table)columnar).getGroup() == group;
        }
    }
}
