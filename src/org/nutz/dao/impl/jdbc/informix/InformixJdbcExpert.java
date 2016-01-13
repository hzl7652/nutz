package org.nutz.dao.impl.jdbc.informix;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.nutz.dao.DB;
import org.nutz.dao.Dao;
import org.nutz.dao.Sqls;
import org.nutz.dao.entity.Entity;
import org.nutz.dao.entity.LinkField;
import org.nutz.dao.entity.MappingField;
import org.nutz.dao.entity.PkType;
import org.nutz.dao.entity.annotation.ColType;
import org.nutz.dao.impl.entity.field.ManyManyLinkField;
import org.nutz.dao.impl.entity.macro.SqlFieldMacro;
import org.nutz.dao.impl.jdbc.AbstractJdbcExpert;
import org.nutz.dao.impl.jdbc.BlobValueAdaptor;
import org.nutz.dao.impl.jdbc.ClobValueAdaptor;
import org.nutz.dao.jdbc.JdbcExpertConfigFile;
import org.nutz.dao.jdbc.ValueAdaptor;
import org.nutz.dao.pager.Pager;
import org.nutz.dao.sql.PItem;
import org.nutz.dao.sql.Pojo;
import org.nutz.dao.sql.Sql;
import org.nutz.dao.util.Pojos;
import org.nutz.filepool.FilePool;
import org.nutz.lang.Strings;

public class InformixJdbcExpert extends AbstractJdbcExpert {

    public InformixJdbcExpert(JdbcExpertConfigFile conf) {
        super(conf);
    }

    public String getDatabaseType() {
        return DB.GBASE8T.name();
    }

    public boolean createEntity(Dao dao, Entity<?> en) {
        StringBuilder sb = new StringBuilder("CREATE TABLE "
                                             + en.getTableName()
                                             + "(");
        // 创建字段
        for (MappingField mf : en.getMappingFields()) {
            if (mf.isReadonly())
                continue;
            sb.append('\n').append(mf.getColumnName());
            sb.append(' ').append(evalFieldType(mf));
            // 非主键的 @Name，应该加入唯一性约束
            if (mf.isName() && en.getPkType() != PkType.NAME) {
                sb.append(" UNIQUE NOT NULL");
            }
            // 普通字段
            else {

                if (mf.isNotNull())
                    sb.append(" NOT NULL");
                if (mf.hasDefaultValue())
                    addDefaultValue(sb, mf);
            }
            sb.append(',');
        }
        // 创建主键
        List<MappingField> pks = en.getPks();
        if (!pks.isEmpty()) {
            sb.append('\n');
            sb.append("PRIMARY KEY (");
            for (MappingField pk : pks) {
                sb.append(pk.getColumnName()).append(',');
            }
            sb.setCharAt(sb.length() - 1, ')');
            sb.append("\n ");
        }

        // 结束表字段设置
        sb.setCharAt(sb.length() - 1, ')');

        // 执行创建语句
        dao.execute(Sqls.create(sb.toString()));
        // 创建索引
        dao.execute(createIndexs(en).toArray(new Sql[0]));
        // 创建关联表
        createRelation(dao, en);
        // 添加注释(表注释与字段注释),informix不支持表和列注释
        // addComment(dao, en, COMMENT_COLUMN);

        return true;
    }

    @Override
    public void createRelation(Dao dao, Entity<?> en) {
        final List<Sql> sqls = new ArrayList<Sql>(5);
        for (LinkField lf : en.visitManyMany(null, null, null)) {
            ManyManyLinkField mm = (ManyManyLinkField) lf;
            if (dao.exists(mm.getRelationName()))
                continue;
            String sql = "CREATE TABLE " + mm.getRelationName() + "(";
            String hostFieldType = mm.getHostField().isId() ? "INTEGER"
                                                           : evalFieldType(mm.getHostField());
            sql += mm.getFromColumnName() + " " + hostFieldType + ",";
            String linkedFieldType = mm.getLinkedField().isId() ? "INTEGER"
                                                               : evalFieldType(mm.getLinkedField());
            sql += mm.getToColumnName() + " " + linkedFieldType;
            sql += ")";
            sqls.add(Sqls.create(sql));
        }
        dao.execute(sqls.toArray(new Sql[sqls.size()]));
    }

    public String evalFieldType(MappingField mf) {
        if (mf.getCustomDbType() != null)
            return mf.getCustomDbType();
        if (mf.isId() && mf.isAutoIncreasement()) {
            return "SERIAL";
        }
        // Mysql 的精度是按照 bit
        if (mf.getColumnType() == ColType.INT) {
            int width = mf.getWidth();
            if (width <= 0) {
                return "INTEGER";
            } else if (width <= 2) {
                return "SMALLINT";
            } else if (width <= 4) {
                return "INTEGER";
            } else if (width <= 8) {
                return "INTEGER";
            }
            return "INT8";
        }
        if (mf.getColumnType() == ColType.BINARY) {
            return "BYTE";
        }

        if (mf.getColumnType() == ColType.VARCHAR) {
            int width = mf.getWidth();
            if (width <= 255) {
                return "VARCHAR(" + mf.getWidth() + ")";
            } else {
                return "LVARCHAR(" + mf.getWidth() + ")";
            }
        }

        if (mf.getColumnType() == ColType.FLOAT) {
            if (mf.getWidth() > 0 && mf.getPrecision() > 0) {
                return "decimal("
                       + mf.getWidth()
                       + ","
                       + mf.getPrecision()
                       + ")";
            } else {
                return "decimal";
            }
        }

        if (mf.getColumnType() == ColType.DATETIME
            || mf.getColumnType() == ColType.TIMESTAMP) {
            return "DATETIME YEAR TO FRACTION";
        }

        if (mf.getColumnType() == ColType.TIME) {
            return "DATETIME HOUR TO FRACTION";
        }
        // 其它的参照默认字段规则 ...
        return super.evalFieldType(mf);
    }

    public void formatQuery(Pojo pojo) {
        Pager pager = pojo.getContext().getPager();
        if (null != pager && pager.getPageNumber() > 0) {

            PItem pi = pojo.getItem(0);
            StringBuilder sb = new StringBuilder();
            pi.joinSql(pojo.getEntity(), sb);
            String str = sb.toString();
            if (str.trim().toLowerCase().startsWith("select")) {
                pojo.setItem(0, Pojos.Items.wrap(str.substring(6)));
            } else
                return;// 以免出错.
            pojo.insertFirst(Pojos.Items.wrapf("select SKIP %d FIRST %d  ",
                                               pager.getOffset(),
                                               pager.getPageSize()));
        }
    }

    @Override
    public void formatQuery(Sql sql) {
        Pager pager = sql.getContext().getPager();
        // 需要进行分页
        if (null != pager && pager.getPageNumber() > 0) {

            if (!sql.getSourceSql().toUpperCase().startsWith("SELECT "))
                return;// 以免出错.
            String xSql = sql.getSourceSql().substring(6);
            String pre = String.format("select SKIP %d FIRST %d  ",
                                       pager.getOffset(),
                                       pager.getPageSize());

            sql.setSourceSql(pre + xSql);
        }
    }

    protected String createResultSetMetaSql(Entity<?> en) {
        return "SELECT FIRST 1 * FROM " + en.getViewName();
    }

    public Pojo fetchPojoId(Entity<?> en, MappingField idField) {
        String customDbType = idField.getCustomDbType();
        String autoSql = null;
        if (Strings.isBlank(customDbType)
            || Strings.equalsIgnoreCase("SERIAL", customDbType)) {
            autoSql = "select dbinfo('sqlca.sqlerrd1') from informix.systables where tabid=1";
        } else {
            autoSql = "select dbinfo('serial8') from informix.systables where tabid=1";
        }

        Pojo autoInfo = new SqlFieldMacro(idField, autoSql);
        autoInfo.setEntity(en);
        return autoInfo;
    }

    @Override
    public ValueAdaptor getAdaptor(MappingField ef) {
        if (ef.getTypeMirror().isBoolean())
            return new InformixBooleanAdaptor();
        // Blob
        if (ef.getTypeMirror().isOf(Blob.class))
            return new InformixBlobValueAdaptor(conf.getPool());
        // Clob
        if (ef.getTypeMirror().isOf(Clob.class)) {
            return new InformixTextValueAdapter(conf.getPool());
        }

        return super.getAdaptor(ef);
    }

}

class InformixBlobValueAdaptor extends BlobValueAdaptor {

    public InformixBlobValueAdaptor(FilePool pool) {
        super(pool);
    }

    public void set(PreparedStatement stat, Object obj, int i)
            throws SQLException {
        if (null == obj) {
            stat.setNull(i, Types.BLOB);
        } else {
            Blob blob = (Blob) obj;
            // 第三个参数必须是int，否则informix会报错
            stat.setBinaryStream(i, blob.getBinaryStream(), (int) blob.length());
        }
    }
}

class InformixTextValueAdapter extends ClobValueAdaptor {

    public InformixTextValueAdapter(FilePool pool) {
        super(pool);
    }

    public void set(PreparedStatement stat, Object obj, int index)
            throws SQLException {
        if (null == obj) {
            stat.setNull(index, Types.CLOB);
        } else {
            Clob clob = (Clob) obj;
            // InputStream ins = clob.getAsciiStream();
            // 第三个参数必须是int，否则informix会报错
            // stat.setAsciiStream(index, ins, (int) clob.length());
            stat.setAsciiStream(index,
                                clob.getAsciiStream(),
                                    (int) clob.length());

        }
    }

}
