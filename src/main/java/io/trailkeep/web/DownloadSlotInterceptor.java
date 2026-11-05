// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.document.CaseAttachmentService.Download;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gives a download's slot back when its request ends, however it ends (#59 review). The body's own {@code finally}
 * is the normal path; this covers a body that never runs: the headers failing on the request thread before the
 * async task exists, or the async request timing out or failing before the task starts. Spring calls {@code
 * afterCompletion} on the first dispatch when no async processing started, and otherwise on the dispatch after it
 * completed, timed out or failed; {@link Download#release()} is idempotent, so a stream that ended normally is not
 * released twice.
 */
@Component
class DownloadSlotInterceptor implements AsyncHandlerInterceptor, WebMvcConfigurer {

    /** The request attribute holding the download a request opened. */
    static final String ATTRIBUTE = DownloadSlotInterceptor.class.getName() + ".download";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/cases/*/attachments/*");
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception failure) {
        if (request.getAttribute(ATTRIBUTE) instanceof Download download) {
            download.release();
        }
    }
}
