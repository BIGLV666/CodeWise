package org.example.serviceai.controller;

import org.example.serviceai.ai.AiFacade;
import org.example.serviceapi.dto.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 平台模型目录接口：只下发模型名（前端把它和 AUTO、用户分组拼成模型选择树），
 * 不下发服务商——用户看到的是"用哪个模型"，不是"用哪家的机器"。
 */
@ExtendWith(MockitoExtension.class)
class AiModelCatalogControllerTest {

    @Mock
    private AiFacade aiFacade;

    @Test
    void returnsPlatformModelsWithoutProviderDetails() {
        when(aiFacade.platformModels()).thenReturn(List.of("deepseek-v4-flash", "glm-5.3"));

        Result<List<String>> result = new AiModelCatalogController(aiFacade).platformModels();

        assertThat(result.getData()).containsExactly("deepseek-v4-flash", "glm-5.3");
    }

    @Test
    void emptyCatalogIsAnEmptyListNotAnError() {
        when(aiFacade.platformModels()).thenReturn(List.of());

        // 一个端点都没配时给空列表：前端只渲染出 AUTO 一项，不该是报错页
        assertThat(new AiModelCatalogController(aiFacade).platformModels().getData()).isEmpty();
    }
}
