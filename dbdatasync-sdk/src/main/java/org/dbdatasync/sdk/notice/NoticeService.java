/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.sdk.notice;


import org.dbdatasync.sdk.model.NoticeMessage;

public interface NoticeService {

    /**
     * 发送通知消息
     */
    void notify(NoticeMessage notificationMessage);

}
