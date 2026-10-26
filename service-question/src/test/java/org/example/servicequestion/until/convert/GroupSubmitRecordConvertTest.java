package org.example.servicequestion.until.convert;

import org.example.serviceapi.dto.user.UserDto;
import org.example.servicequestion.entry.GroupSubmitRecord;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.vo.MiniGroupSubmitRecordVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 小组提交记录组装：昵称回退、用户缺失降级。
 */
class GroupSubmitRecordConvertTest {

    @Test
    @DisplayName("有昵称用昵称，没昵称回退 userName")
    void prefersNickNameThenUserName() {
        MiniGroupSubmitRecordVo withNick = GroupSubmitRecordConvert.toMiniGroupSubmitRecordVo(
                groupRecord(), submitRecord(), user("nick", "avatar.png"));
        assertThat(withNick.nickName()).isEqualTo("nick");
        assertThat(withNick.avatarUrl()).isEqualTo("avatar.png");

        MiniGroupSubmitRecordVo withoutNick = GroupSubmitRecordConvert.toMiniGroupSubmitRecordVo(
                groupRecord(), submitRecord(), user(null, "avatar.png"));
        assertThat(withoutNick.nickName()).isEqualTo("userName");
    }

    @Test
    @DisplayName("用户已删除（userDto=null）：昵称/头像为 null，不抛 NPE")
    void nullUserDegradesProfile() {
        MiniGroupSubmitRecordVo vo = GroupSubmitRecordConvert.toMiniGroupSubmitRecordVo(
                groupRecord(), submitRecord(), null);

        assertThat(vo.groupSubmitRecordId()).isEqualTo(1L);
        assertThat(vo.submitRecordId()).isEqualTo(2L);
        assertThat(vo.questionId()).isEqualTo(3L);
        assertThat(vo.questionTitle()).isEqualTo("两数之和");
        assertThat(vo.nickName()).isNull();
        assertThat(vo.avatarUrl()).isNull();
        assertThat(vo.status()).isEqualTo("AC");
    }

    private static GroupSubmitRecord groupRecord() {
        return GroupSubmitRecord.builder()
                .groupSubmitRecordId(1L)
                .submitRecordId(2L)
                .groupId(88L)
                .userId(9L)
                .build();
    }

    private static SubmitRecord submitRecord() {
        SubmitRecord record = SubmitRecord.builder()
                .submitRecordId(2L)
                .questionId(3L)
                .questionTitle("两数之和")
                .build();
        record.setJudgeStatus("AC");
        return record;
    }

    private static UserDto user(String nickName, String avatarUrl) {
        UserDto user = new UserDto();
        user.setUserName("userName");
        user.setNickName(nickName);
        user.setAvatarUrl(avatarUrl);
        return user;
    }
}
