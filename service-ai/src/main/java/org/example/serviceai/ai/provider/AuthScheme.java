package org.example.serviceai.ai.provider;

/**
 * 鉴权方式：凭证放在哪个请求头、怎么拼值。
 *
 * <p>当前所有接入方式都是 Bearer（OpenCode Go 也不例外——它的 {@code x-opencode-session}
 * 是路由/缓存键而不是鉴权头，那种头归 {@link AiProtocol#perRequestHeaders}）。要用别的鉴权
 * （如 {@code api-key}）就在这里加一个工厂方法，Transport 不需要知道任何鉴权语义。</p>
 */
public record AuthScheme(String headerName, String valuePrefix) {

    private static final String AUTHORIZATION = "Authorization";

    public static AuthScheme bearer() {
        return new AuthScheme(AUTHORIZATION, "Bearer ");
    }

    public String format(String credential) {
        return valuePrefix + credential;
    }
}
