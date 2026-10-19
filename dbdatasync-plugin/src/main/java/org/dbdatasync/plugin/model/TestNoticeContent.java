/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.plugin.model;


import org.dbdatasync.sdk.enums.NoticeTypeEnum;
import org.dbdatasync.sdk.model.NoticeContent;

/**
 * 测试通知消息
 *
 * @Author AE86
 * @Version 1.0.0
 * @Date 2026-03-04 19:00
 */
public class TestNoticeContent extends NoticeContent {

    private String content;

    public TestNoticeContent() {
        setNoticeType(NoticeTypeEnum.TEST_MESSAGE);
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

}
