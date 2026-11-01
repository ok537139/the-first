package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /**
     * 查询当前用户某张秒杀券的下单结果：已下单、处理中，或者没有资格
     */
    Result querySeckillResult(Long voucherId);

    /**
     * @return false 表示 MySQL 库存不足，订单已回滚，需要退回 Redis 中的资格
     */
    boolean createVoucherOrder(VoucherOrder voucherOrder);

    /**
     * 订单最终没有建成：把用户移出已购集合，restoreStock 为 true 时加回 Redis 库存。可以重复执行
     */
    void releaseSeckill(VoucherOrder voucherOrder, boolean restoreStock);
}
