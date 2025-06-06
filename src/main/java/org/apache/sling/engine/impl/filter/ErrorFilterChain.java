/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.engine.impl.filter;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.api.servlets.JakartaErrorHandler;
import org.apache.sling.engine.impl.SlingJakartaHttpServletResponseImpl;
import org.apache.sling.engine.impl.request.DispatchingInfo;

public class ErrorFilterChain extends AbstractSlingFilterChain {

    private static final String RECURSION_ATTRIBUTE = ErrorFilterChain.class.getName() + ".RECURSION";

    private static final String PREFIX_COMMITTED = "handleError: Response already committed; cannot send error ";

    private static final String PREFIX_RECURSION = "handleError: Recursive invocation. Not further handling status ";

    private enum Mode {
        THROWABLE,
        STATUS
    };

    private final int status;

    private final String message;

    private final JakartaErrorHandler errorHandler;

    private final Throwable throwable;

    private final Mode mode;

    private boolean firstCall = true;

    public ErrorFilterChain(
            final FilterHandle[] filters,
            final JakartaErrorHandler errorHandler,
            final int status,
            final String message) {
        super(filters);
        this.mode = Mode.STATUS;
        this.status = status;
        this.message = message;
        this.errorHandler = errorHandler;
        this.throwable = null;
    }

    public ErrorFilterChain(final FilterHandle[] filters, final JakartaErrorHandler errorHandler, final Throwable t) {
        super(filters);
        this.mode = Mode.THROWABLE;
        this.status = 0;
        this.message = null;
        this.throwable = t;
        this.errorHandler = errorHandler;
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response)
            throws ServletException, IOException {
        if (firstCall) {
            if (request.getAttribute(RECURSION_ATTRIBUTE) != null) {
                if (this.mode == Mode.STATUS) {
                    if (message == null) {
                        LOG.warn(PREFIX_RECURSION.concat(String.valueOf(status)));
                    } else {
                        LOG.warn(PREFIX_RECURSION
                                .concat(String.valueOf(status))
                                .concat(" : ")
                                .concat(message));
                    }
                } else {
                    if (throwable.getMessage() != null) {
                        LOG.warn(PREFIX_RECURSION.concat(throwable.getMessage()), throwable);
                    } else {
                        LOG.warn(PREFIX_RECURSION.concat(throwable.getClass().getName()), throwable);
                    }
                }
                return;
            }
            request.setAttribute(RECURSION_ATTRIBUTE, "true");
            firstCall = false;
            // do nothing if response is already committed
            if (response.isCommitted()) {
                if (this.mode == Mode.STATUS) {
                    if (message == null) {
                        LOG.warn(PREFIX_COMMITTED.concat(String.valueOf(status)));
                    } else {
                        LOG.warn(PREFIX_COMMITTED
                                .concat(String.valueOf(status))
                                .concat(" : ")
                                .concat(message));
                    }
                } else {
                    if (throwable.getMessage() != null) {
                        LOG.warn(PREFIX_COMMITTED.concat(throwable.getMessage()), throwable);
                    } else {
                        LOG.warn(PREFIX_COMMITTED.concat(throwable.getClass().getName()), throwable);
                    }
                }
                return;
            }

            // reset the response to clear headers and body
            if (response instanceof SlingJakartaHttpServletResponseImpl) {
                SlingJakartaHttpServletResponseImpl slingResponse = (SlingJakartaHttpServletResponseImpl) response;
                /*
                 * Below section stores the original dispatching info for later restoration.
                 * This is necessary to ensure that the dispatching info is set to ERROR
                 * while the error is being handled and the response is reset, but restored to
                 * its original state after the error handling is complete. This is important
                 * for correct request processing and to avoid side effects on subsequent
                 * filters or request processing steps.
                 */
                DispatchingInfo originalInfo = null;
                try {
                    originalInfo = slingResponse.getRequestData().getDispatchingInfo();
                    final DispatchingInfo dispatchInfo = new DispatchingInfo(DispatcherType.ERROR);
                    slingResponse.getRequestData().setDispatchingInfo(dispatchInfo);
                    response.reset();
                    super.doFilter(request, response);
                } finally {
                    slingResponse.getRequestData().setDispatchingInfo(originalInfo);
                }
            } else {
                response.reset();
                super.doFilter(request, response);
            }
        } else {
            super.doFilter(request, response);
        }
    }

    protected void render(final SlingJakartaHttpServletRequest request, final SlingJakartaHttpServletResponse response)
            throws IOException, ServletException {
        if (this.mode == Mode.STATUS) {
            this.errorHandler.handleError(this.status, this.message, request, response);
        } else {
            this.errorHandler.handleError(this.throwable, request, response);
        }
    }
}
