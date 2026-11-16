package dev.raggateway.auth;

import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Injects the caller authenticated by {@link ApiKeyAuthInterceptor} into any handler parameter of type
 * {@link ApiKeyPrincipal}. A handler outside the interceptor's paths that asks for a caller gets 401
 * rather than a null.
 */
@Component
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return ApiKeyPrincipal.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object caller = webRequest.getAttribute(ApiKeyAuthInterceptor.CALLER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (caller instanceof ApiKeyPrincipal principal) return principal;
        throw new GatewayException(ErrorCode.UNAUTHORIZED, "authentication required");
    }
}
