package dev.kualu.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * Long-term caching for fingerprinted build assets. Vite puts a content hash in
 * every {@code /assets/} filename, so they can be cached forever; everything else
 * (index.html, the API) keeps Spring Security's no-store default, which
 * {@code CacheControlHeadersWriter} only applies when the response carries no
 * cache header of its own. Deploys therefore show up immediately while repeat
 * visits skip re-downloading the bundle.
 */
@Configuration
public class WebCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
    }
}
