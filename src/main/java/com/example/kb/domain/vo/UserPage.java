package com.example.kb.domain.vo;

import java.util.List;

/**
 * 用户列表页的一次性数据包：列表 + 几个统计数字。
 *
 * <p>为什么把统计和列表捆在一起返回：后台那个页面一进来就要显示
 * 「共 N 个账号，其中 M 个今天注册过」，拆两个接口就是两倍往返，
 * 而且两个数字之间可能不一致（中间有人注册了）。</p>
 *
 * @param items      当前页的用户
 * @param total      账号总数
 * @param adminCount 其中站主账号数（正常应该恒为 1）
 * @param newIn7Days 最近 7 天新注册数
 * @param page       当前页码，从 1 开始
 * @param size       每页条数
 */
public record UserPage(List<UserRowVO> items,
                       long total,
                       long adminCount,
                       long newIn7Days,
                       int page,
                       int size) {
}
