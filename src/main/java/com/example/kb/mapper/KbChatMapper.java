package com.example.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.kb.domain.po.KbChat;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * kb_chat 表访问（访客问答记录）。
 *
 * <p>归属地不存 kb_chat 里，而是查询时 {@code LEFT JOIN LATERAL} 从
 * kb_visit 取同 IP 最近一条 DONE 的记录。理由：问答记录和访问记录是两条
 * 独立写入，同一 IP 的归属地只该由 {@code GeoIpService} 补一次，
 * 存两份早晚会不同步。两个带 LATERAL 的查询在
 * {@code resources/mapper/KbChatMapper.xml}。</p>
 */
@Mapper
public interface KbChatMapper extends BaseMapper<KbChat> {

    /**
     * 回答写完，回填空缺字段。
     *
     * <p>⚠️ 这里刻意不用 {@code updateById}：{@code answer} 可能为空串，
     * {@code elapsedMs} 可能是 null，而 {@code updateById} 对 null 是
     * 「不更新」。{@code finish} 的语义是「这次写入就是最终结果」，
     * 必须把每个字段都显式写进去。</p>
     */
    @Update("UPDATE kb_chat SET answer = #{answer}, answer_chars = #{answerChars},"
            + " source_count = #{sourceCount}, elapsed_ms = #{elapsedMs},"
            + " complete = #{complete} WHERE id = #{id}")
    int finish(@Param("id") long id,
               @Param("answer") String answer,
               @Param("answerChars") int answerChars,
               @Param("sourceCount") int sourceCount,
               @Param("elapsedMs") Integer elapsedMs,
               @Param("complete") boolean complete);

    /** 后台列表（onlyVisitor=true 时排除站主自己的提问） */
    List<ChatWithLocation> selectRecentWithLocation(@Param("limit") int limit,
                                                    @Param("offset") int offset,
                                                    @Param("onlyVisitor") boolean onlyVisitor);

    @Select("<script>SELECT count(*) FROM kb_chat"
            + "<if test='onlyVisitor'> WHERE is_admin = FALSE</if></script>")
    long countChats(@Param("onlyVisitor") boolean onlyVisitor);

    ChatWithLocation selectOneWithLocation(@Param("id") long id);

    @Delete("DELETE FROM kb_chat WHERE id = #{id}")
    int deleteChatById(@Param("id") long id);

    @Delete("DELETE FROM kb_chat")
    int deleteAllChats();

    /**
     * 问答记录 + 关联出来的归属地（扁平结构，给 MyBatis 直接映射）。
     *
     * <p>⚠️ 不做成 record：MyBatis 要无参构造 + setter 才能自动映射，
     * record 只有全参构造，除非另写 ResultMap 否则映射不上。
     * 这也是为什么 DTO 是 record、而数据库行对象是普通类。</p>
     */
    class ChatWithLocation {

        private Long id;
        private String ip;
        private String question;
        private String answer;
        private String sources;
        private int sourceCount;
        private Integer elapsedMs;
        private Integer answerChars;
        private boolean complete;
        private boolean admin;
        private OffsetDateTime createdAt;
        private String country;
        private String region;
        private String city;

        /** 取内层实体（service 里原写法是 row.chat()） */
        public KbChat chat() {
            return toChat();
        }
        /** 转回实体，供 service 层复用 {@code ChatLogDto.of(entity, location)} */
        public KbChat toChat() {
            KbChat c = new KbChat();
            c.setId(id);
            c.setIp(ip);
            c.setQuestion(question);
            c.setAnswer(answer);
            c.setSourcesJson(sources);
            c.setSourceCount(sourceCount);
            c.setElapsedMs(elapsedMs);
            c.setAnswerChars(answerChars);
            c.setComplete(complete);
            c.setAdmin(admin);
            c.setCreatedAt(createdAt);
            return c;
        }

        /** 拼成「中国 山西 晋中」，全空返回 null */
        public String locationText() {
            StringBuilder sb = new StringBuilder();
            for (String part : new String[]{country, region, city}) {
                if (part != null && !part.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(part);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String answer) {
            this.answer = answer;
        }

        public String getSources() {
            return sources;
        }

        public void setSources(String sources) {
            this.sources = sources;
        }

        public int getSourceCount() {
            return sourceCount;
        }

        public void setSourceCount(int sourceCount) {
            this.sourceCount = sourceCount;
        }

        public Integer getElapsedMs() {
            return elapsedMs;
        }

        public void setElapsedMs(Integer elapsedMs) {
            this.elapsedMs = elapsedMs;
        }

        public Integer getAnswerChars() {
            return answerChars;
        }

        public void setAnswerChars(Integer answerChars) {
            this.answerChars = answerChars;
        }

        public boolean isComplete() {
            return complete;
        }

        public void setComplete(boolean complete) {
            this.complete = complete;
        }

        public boolean isAdmin() {
            return admin;
        }

        public void setAdmin(boolean admin) {
            this.admin = admin;
        }

        public OffsetDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(OffsetDateTime createdAt) {
            this.createdAt = createdAt;
        }

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }
    }

    // ===== 旧 Repository API 兼容（service 里调用点保持原样，只换类型） =====

    /** 旧 Repository API 兼容：按 id 查，返回 Optional */
    default java.util.Optional<ChatWithLocation> findById(long id) {
        return java.util.Optional.ofNullable(selectOneWithLocation(id));
    }

    /** 旧 Repository API 兼容：分页（带归属地） */
    default java.util.List<ChatWithLocation> findRecent(int limit, int offset, boolean onlyVisitor) {
        return selectRecentWithLocation(limit, offset, onlyVisitor);
    }

    /** 旧 Repository API 兼容：总数 */
    default long count(boolean onlyVisitor) {
        return countChats(onlyVisitor);
    }

    /** 旧 Repository API 兼容：删一条 */
    default int deleteById(long id) {
        return deleteChatById(id);
    }

    /** 旧 Repository API 兼容：全清（站主后台「清空问答记录」） */
    default int deleteAll() {
        return deleteAllChats();
    }

}
