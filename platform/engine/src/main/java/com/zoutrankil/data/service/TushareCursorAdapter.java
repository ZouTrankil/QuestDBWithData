package com.zoutrankil.data.service;

import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.domain.PageContract;

import java.util.function.BooleanSupplier;

/** 市场为没有通用 offset 分页的来源提供明确游标协议。 */
public interface TushareCursorAdapter {
    boolean supports(PageContract contract);

    PageExecutor.Fetcher fetcher(
            TushareClient client, PageContract contract, BooleanSupplier cancelled);
}
