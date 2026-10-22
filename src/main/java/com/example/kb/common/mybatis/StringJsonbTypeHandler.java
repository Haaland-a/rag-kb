package com.example.kb.common.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

/**
 * 单个 String 直接写 PG 的 jsonb 列。
 *
 * <p>和 {@link AbstractListJsonbTypeHandler} 的区别：那个把 List 序列化成 JSON
 * 数组，这个不序列化 —— 传进来的 String 本身就是合法 JSON（
 * {@code kb_chat.sources} 存的是 {@code SourceRef} 数组的 JSON 文本，
 * 但字段类型是 String，业务代码自己控制格式）。</p>
 *
 * <p>⚠️ 为什么必须用 {@link PGobject} 而不是 {@code ps.setString()}：
 * PG 的 {@code varchar} 不能隐式转 {@code jsonb}，会报
 * {@code column "x" is of type jsonb but expression is of type character varying}。
 * 这是 MyBatis-Plus 自带 JacksonTypeHandler 的同一个坑。</p>
 */
public class StringJsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject json = new PGobject();
        json.setType("jsonb");
        json.setValue(parameter);
        ps.setObject(i, json);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
