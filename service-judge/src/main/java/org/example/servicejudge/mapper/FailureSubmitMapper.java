package org.example.servicejudge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.servicejudge.entry.FailureSubmit;

@Mapper
public interface FailureSubmitMapper extends BaseMapper<FailureSubmit> {

    /**
     * 抢占待处理或失败的记录，并在开始本次重试时递增重试次数。
     */
    int updateStatusToRetry(@Param("failureSubmitId") Long failureSubmitId);

    /**
     * 条件更新状态；更新为成功时同时清空上次错误。
     */
    int updateStatus(
            @Param("failureSubmitId") Long failureSubmitId,
            @Param("status") Integer status);

    /**
     * 记录重试失败状态和异常摘要。
     */
    int updateFailureStatus(
            @Param("failureSubmitId") Long failureSubmitId,
            @Param("status") Integer status,
            @Param("lastError") String lastError);

    /**
     * 定时补偿超过阈值仍处于重试中或失败状态的记录。
     */
    int reconcileStaleSubmits();
}
