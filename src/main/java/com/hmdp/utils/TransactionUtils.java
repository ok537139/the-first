package com.hmdp.utils;

import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class TransactionUtils {

    /**
     * 当前有事务时，在事务提交后执行；没有事务时立即执行。
     * 用于删除缓存等外部操作：在事务内执行的话，提交前的读请求可能把旧数据写回缓存，
     * 事务回滚时外部操作也无法撤销。
     */
    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }


        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
