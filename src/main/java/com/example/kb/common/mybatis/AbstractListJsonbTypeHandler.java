package com.example.kb.common.mybatis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * PostgreSQL {@code jsonb} 列与 Java 集合之间的通用转换器。
 *
 * <p>为什么不直接用 MyBatis-Plus 自带的 {@code JacksonTypeHandler}：
 * 它是用 {@code ps.setString(...)} 写入的，而 PG 的 jsonb 列不接受 varchar，
 * 驱动会抛「column is of type jsonb but expression is of type character varying」。
 * 这里改用 {@link PGobject} 明确标成 jsonb 类型，才能正常写入。</p>
 *
 * <p>读取一侧反过来：{@code rs.getString()} 对 jsonb 是能用的（驱动会转成文本），
 * 所以只需要在写入时特殊处理。</p>
 *
 * <p>⚠️ 泛型在 MyBatis 里会被擦除，所以必须有具体的子类
 * （{@code StringListJsonbTypeHandler} 这种），不能直接注册这个抽象类。</p>
 *
 * @param <T> 集合元素类型
 */
public abstract class AbstractListJsonbTypeHandler<T> extends BaseTypeHandler<List<T>> {

    /** 每张表一个实例，线程安全（ObjectMapper 无状态） */
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final TypeReference<List<T>> typeRef;

    protected AbstractListJsonbTypeHandler(TypeReference<List<T>> typeRef) {
        this.typeRef = typeRef;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<T> parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject json = new PGobject();
        json.setType("jsonb");
        try {
            json.setValue(MAPPER.writeValueAsString(parameter));
        }
        catch (Exception e) {
            throw new SQLException("序列化 jsonb 列失败: " + e.getMessage(), e);
        }
        ps.setObject(i, json);
    }

    @Override
    public List<T> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<T> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<T> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    /**
     * 解析失败返回空列表而不是抛异常。
     *
     * <p>理由：这一列是展示用内容（标签、外链、引用来源），
     * 一条脏数据不该让整个页面 500。日志由调用方在写入时保证，读的时候宽容一点。</p>
     */
    private List<T> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(raw, typeRef);
        }
        catch (Exception e) {
            return List.of();
        }
    }
}
