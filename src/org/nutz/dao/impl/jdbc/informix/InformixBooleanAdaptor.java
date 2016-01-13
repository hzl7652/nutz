package org.nutz.dao.impl.jdbc.informix;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Set;

import org.nutz.dao.jdbc.ValueAdaptor;
import org.nutz.lang.Lang;
import org.nutz.lang.Strings;

/**
 * 对 Oracle，Types.BOOLEAN 对于 setNull 是不工作的 其他的数据库都没有这个问题，<br>
 * 所以，只好把类型设成 INTEGER了
 */
public class InformixBooleanAdaptor implements ValueAdaptor {

    private Set<String> informixBooleanStrs;

    public InformixBooleanAdaptor(){
        informixBooleanStrs = Lang.set("true", "yes", "on");
    }
    public Object get(ResultSet rs, String colName) throws SQLException {
        boolean re = rs.getBoolean(colName);
        return rs.wasNull() ? null : re;
    }

    public void set(PreparedStatement stat, Object obj, int i) throws SQLException {
        if (null == obj) {
            stat.setNull(i, Types.INTEGER);
        } else {
            boolean v;
            if (obj instanceof Boolean)
                v = (Boolean) obj;
            else if (obj instanceof Number)
                v = ((Number) obj).intValue() > 0;
            else if (obj instanceof Character)
                v = Character.toUpperCase((Character) obj) == 'T';
            else {
                String str = obj.toString();
                str = Strings.sBlank(str);
                v = informixBooleanStrs.contains(str);
            }
            stat.setBoolean(i, v);
        }
    }

}
