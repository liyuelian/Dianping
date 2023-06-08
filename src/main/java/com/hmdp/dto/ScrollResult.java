package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * 滚动分页模型
 *
 * @author 李
 * @version 1.0
 */
@Data
public class ScrollResult {
    //小于指定时间戳的笔记集合
    private List<?> list;
    //本次查询推送的最小时间戳
    private Long minTime;
    //偏移量
    private Integer offset;
}
