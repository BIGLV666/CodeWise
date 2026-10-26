package org.example.servicequestion.until.convert;

import org.example.serviceapi.dto.user.UserDto;
import org.example.servicequestion.entry.GroupSubmitRecord;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.vo.MiniGroupSubmitRecordVo;

public class GroupSubmitRecordConvert {

    private GroupSubmitRecordConvert() {
    }

    /**
     * 组装小组提交记录列表项。
     *
     * <p>用户可能已注销/被删除，此时 {@code userDto} 为 null：昵称与头像降级为
     * null，不能让整页列表失败。</p>
     */
    public static MiniGroupSubmitRecordVo toMiniGroupSubmitRecordVo(
            GroupSubmitRecord groupSubmitRecord,
            SubmitRecord submitRecord,
            UserDto userDto) {
        String nickName = null;
        String avatarUrl = null;
        if (userDto != null) {
            nickName = userDto.getNickName() == null ? userDto.getUserName() : userDto.getNickName();
            avatarUrl = userDto.getAvatarUrl();
        }
        return new MiniGroupSubmitRecordVo(
                groupSubmitRecord.getGroupSubmitRecordId(),
                submitRecord.getSubmitRecordId(),
                submitRecord.getQuestionId(),
                submitRecord.getQuestionTitle(),
                nickName,
                avatarUrl,
                submitRecord.getJudgeStatus(),
                groupSubmitRecord.getCreateTime()
        );
    }
}
