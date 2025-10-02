/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.transport.grpc.spi;

import io.grpc.ServerInterceptor;

/**
 * SPI interface for ordered gRPC interceptors.
 * Each interceptor must have a unique order value to ensure proper execution sequence.
 */
public interface OrderedGrpcInterceptor {

    /**
     * Returns the execution order of this interceptor.
     * Lower values execute first. Each interceptor must have a unique order.
     *
     * @return The execution order
     */
    int order();

    /**
     * Returns the gRPC ServerInterceptor implementation.
     *
     * @return The ServerInterceptor instance
     */
    ServerInterceptor getInterceptor();
}

