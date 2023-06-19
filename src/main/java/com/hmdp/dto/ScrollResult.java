package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * 滚动分页
 */

@Data
public class ScrollResult {
    private List<?> list;       // 使用不确定类型，虽然只是用于博客，但是提供了更多的可扩展性
    private Long minTime;       // 最小时间，也就是滚动查询的最后一条记录
    private Integer offset;     // 偏移量，第一页是0，第二页是最小时间对应的博客的数量，避免重复查询
}
