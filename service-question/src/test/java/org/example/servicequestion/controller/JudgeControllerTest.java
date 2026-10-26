package org.example.servicequestion.controller;

import org.example.servicequestion.dto.CursorPageResult;
import org.example.servicequestion.service.JudgeService;
import org.example.servicequestion.service.SubmitRecordService;
import org.example.servicequestion.vo.MiniGroupSubmitRecordVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 小组提交记录分页接口：路径、参数绑定与返回体。
 */
@ExtendWith(MockitoExtension.class)
class JudgeControllerTest {

    @Mock
    private JudgeService judgeService;
    @Mock
    private SubmitRecordService submitRecordService;

    @InjectMocks
    private JudgeController judgeController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(judgeController).build();
    }

    @Test
    @DisplayName("GET /group/{groupId}/submit-records：透传游标与 pageSize")
    void cursorMiniGroupSubmitRecords() throws Exception {
        MiniGroupSubmitRecordVo vo = new MiniGroupSubmitRecordVo(
                1L, 2L, 3L, "两数之和", "nick", "avatar.png", "AC", LocalDateTime.now());
        CursorPageResult<MiniGroupSubmitRecordVo> page = CursorPageResult
                .<MiniGroupSubmitRecordVo>builder()
                .records(List.of(vo))
                .nextCursor(1L)
                .hasNext(false)
                .build();
        when(submitRecordService.cursorMiniGroupSubmitRecord(88L, 100L, 20)).thenReturn(page);

        mockMvc.perform(get("/api/question/group/88/submit-records")
                        .param("lastId", "100")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].nickName").value("nick"))
                .andExpect(jsonPath("$.data.records[0].status").value("AC"))
                .andExpect(jsonPath("$.data.nextCursor").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(submitRecordService).cursorMiniGroupSubmitRecord(88L, 100L, 20);
    }
}
