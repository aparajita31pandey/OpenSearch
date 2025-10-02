# transport-grpc-spi

Service Provider Interface (SPI) for the OpenSearch gRPC transport module. This module provides interfaces and utilities that allow external plugins to extend the gRPC transport functionality.

## Overview

The `transport-grpc-spi` module enables plugin developers to:
- Implement custom query converters for gRPC transport
- Extend gRPC protocol buffer handling
- Register custom query types that can be processed via gRPC
- Register gRPC interceptors for request/response processing

## Key Components

### QueryBuilderProtoConverter

Interface for converting protobuf query messages to OpenSearch QueryBuilder objects.

```java
public interface QueryBuilderProtoConverter {
    QueryContainer.QueryContainerCase getHandledQueryCase();
    QueryBuilder fromProto(QueryContainer queryContainer);
}
```

### QueryBuilderProtoConverterRegistry

Interface for accessing the query converter registry. This provides a clean abstraction for plugins that need to convert nested queries without exposing internal implementation details.

## Usage for Plugin Developers

### 1. Add Dependency

Add the SPI dependency to your plugin's `build.gradle`:

```gradle
dependencies {
    compileOnly 'org.opensearch.plugin:transport-grpc-spi:${opensearch.version}'
    compileOnly 'org.opensearch:protobufs:${protobufs.version}'
    compileOnly 'io.grpc:grpc-api:${versions.grpc}'
}
```

### 2. Implement Custom Query Converter

```java
public class MyCustomQueryConverter implements QueryBuilderProtoConverter {

    @Override
    public QueryContainer.QueryContainerCase getHandledQueryCase() {
        return QueryContainer.QueryContainerCase.MY_CUSTOM_QUERY;
    }

    @Override
    public QueryBuilder fromProto(QueryContainer queryContainer) {
        // Convert your custom protobuf query to QueryBuilder
        MyCustomQuery customQuery = queryContainer.getMyCustomQuery();
        return new MyCustomQueryBuilder(customQuery.getField(), customQuery.getValue());
    }
}
```

### 3. Register Your Converter

In your plugin's main class, return the converter from createComponents:

```java
public class MyPlugin extends Plugin {

    @Override
    public Collection<Object> createComponents(Client client, ClusterService clusterService,
                                             ThreadPool threadPool, ResourceWatcherService resourceWatcherService,
                                             ScriptService scriptService, NamedXContentRegistry xContentRegistry,
                                             Environment environment, NodeEnvironment nodeEnvironment,
                                             NamedWriteableRegistry namedWriteableRegistry,
                                             IndexNameExpressionResolver indexNameExpressionResolver,
                                             Supplier<RepositoriesService> repositoriesServiceSupplier) {

        // Return your converter instance - the transport-grpc plugin will discover and register it
        return Collections.singletonList(new MyCustomQueryConverter());
    }
}
```

**Step 3b: Create SPI Registration File**

Create a file at `src/main/resources/META-INF/services/org.opensearch.transport.grpc.spi.QueryBuilderProtoConverter`:

```
org.opensearch.mypackage.MyCustomQueryConverter
```

**Step 3c: Declare Extension in Plugin Descriptor**

In your `plugin-descriptor.properties`, declare that your plugin extends transport-grpc:

```properties
extended.plugins=transport-grpc
```

### 4. Accessing the Registry (For Complex Queries)

If your converter needs to handle nested queries (like k-NN's filter clause), you'll need access to the registry to convert other query types. The transport-grpc plugin will inject the registry into your converter.

```java
public class MyCustomQueryConverter implements QueryBuilderProtoConverter {

    private QueryBuilderProtoConverterRegistry registry;

    @Override
    public void setRegistry(QueryBuilderProtoConverterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public QueryBuilder fromProto(QueryContainer queryContainer) {
        MyCustomQuery customQuery = queryContainer.getMyCustomQuery();

        MyCustomQueryBuilder builder = new MyCustomQueryBuilder(
            customQuery.getField(),
            customQuery.getValue()
        );

        // Handle nested queries using the injected registry
        if (customQuery.hasFilter()) {
            QueryContainer filterContainer = customQuery.getFilter();
            QueryBuilder filterQuery = registry.fromProto(filterContainer);
            builder.filter(filterQuery);
        }

        return builder;
    }
}
```

**Registry Injection Pattern**

**How k-NN Now Accesses Built-in Converters**:

The gRPC plugin **injects the populated registry** into converters that need it:

```java
// 1. Converter interface has a default setRegistry method
public interface QueryBuilderProtoConverter {
    QueryBuilder fromProto(QueryContainer queryContainer);

    default void setRegistry(QueryBuilderProtoConverterRegistry registry) {
        // By default, converters don't need a registry
        // Converters that handle nested queries should override this method
    }
}

// 2. GrpcPlugin injects registry into loaded extensions
for (QueryBuilderProtoConverter converter : queryConverters) {
    // Inject the populated registry into the converter
    converter.setRegistry(queryRegistry);

    // Register the converter
    queryRegistry.registerConverter(converter);
}
```

**Registry Access Pattern for Converters with Nested Queries**:
```java
public class KNNQueryBuilderProtoConverter implements QueryBuilderProtoConverter {

    private QueryBuilderProtoConverterRegistry registry;

    @Override
    public void setRegistry(QueryBuilderProtoConverterRegistry registry) {
        this.registry = registry;
        // Pass the registry to utility classes that need it
        KNNQueryBuilderProtoUtils.setRegistry(registry);
    }

    @Override
    public QueryBuilder fromProto(QueryContainer queryContainer) {
        // The utility class can now convert nested queries using the injected registry
        return KNNQueryBuilderProtoUtils.fromProto(queryContainer.getKnn());
    }
}
```


## Testing

### Unit Tests

```bash
./gradlew :modules:transport-grpc:spi:test
```

### Testing Your Custom Converter

```java
@Test
public void testCustomQueryConverter() {
    MyCustomQueryConverter converter = new MyCustomQueryConverter();

    // Create test protobuf query
    QueryContainer queryContainer = QueryContainer.newBuilder()
        .setMyCustomQuery(MyCustomQuery.newBuilder()
            .setField("test_field")
            .setValue("test_value")
            .build())
        .build();

    // Convert and verify
    QueryBuilder result = converter.fromProto(queryContainer);
    assertThat(result, instanceOf(MyCustomQueryBuilder.class));

    MyCustomQueryBuilder customQuery = (MyCustomQueryBuilder) result;
    assertEquals("test_field", customQuery.fieldName());
    assertEquals("test_value", customQuery.value());
}
```

## Real-World Example: k-NN Plugin
See the k-NN plugin https://github.com/opensearch-project/k-NN/pull/2833/files for an example on how to use this SPI, including handling nested queries.

**1. Dependency in build.gradle:**
```gradle
compileOnly "org.opensearch.plugin:transport-grpc-spi:${opensearch.version}"
compileOnly "org.opensearch:protobufs:0.8.0"
```

**2. Converter Implementation with Registry Access:**
```java
public class KNNQueryBuilderProtoConverter implements QueryBuilderProtoConverter {

    private QueryBuilderProtoConverterRegistry registry;

    @Override
    public void setRegistry(QueryBuilderProtoConverterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public QueryContainer.QueryContainerCase getHandledQueryCase() {
        return QueryContainer.QueryContainerCase.KNN;
    }

    @Override
    public QueryBuilder fromProto(QueryContainer queryContainer) {
        KnnQuery knnQuery = queryContainer.getKnn();

        KNNQueryBuilder builder = new KNNQueryBuilder(
            knnQuery.getField(),
            knnQuery.getVectorList().toArray(new Float[0]),
            knnQuery.getK()
        );

        // Handle nested filter query using injected registry
        if (knnQuery.hasFilter()) {
            QueryContainer filterContainer = knnQuery.getFilter();
            QueryBuilder filterQuery = registry.fromProto(filterContainer);
            builder.filter(filterQuery);
        }

        return builder;
    }
}
```

**3. Plugin Registration:**
```java
// In KNNPlugin.createComponents()
KNNQueryBuilderProtoConverter knnQueryConverter = new KNNQueryBuilderProtoConverter();
return ImmutableList.of(knnStats, knnQueryConverter);
```

**4. SPI File:**
```
# src/main/resources/META-INF/services/org.opensearch.transport.grpc.spi.QueryBuilderProtoConverter
org.opensearch.knn.grpc.proto.request.search.query.KNNQueryBuilderProtoConverter
```

**Why k-NN needs the registry:**
The k-NN query's `filter` field is a `QueryContainer` protobuf type that can contain any query type (MatchAll, Term, Terms, etc.). The k-NN converter needs access to the registry to convert these nested queries to their corresponding QueryBuilder objects.

## gRPC Interceptor Usage

### 1. Implement Custom Interceptor

```java
public class AuthInterceptor implements OrderedGrpcInterceptor {

    @Override
    public int getOrder() {
        return 10; // Lower values execute first
    }

    @Override
    public ServerInterceptor getInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next
            ) {
                // Authentication logic
                String token = headers.get(AUTH_TOKEN_KEY);
                if (!isValidToken(token)) {
                    call.close(Status.UNAUTHENTICATED.withDescription("Invalid token"), new Metadata());
                    return new ServerCall.Listener<ReqT>() {};
                }

                return next.startCall(call, headers);
            }
        };
    }

    @Override
    public boolean isIgnoreFailure() {
        return false; // Critical interceptor - failures break the chain
    }
}
```

### 2. Implement Interceptor Provider

```java
public class MyInterceptorProvider implements GrpcInterceptorProvider {

    @Override
    public List<OrderedGrpcInterceptor> getOrderedGrpcInterceptors() {
        return List.of(
            new AuthInterceptor(),           // Order 10
            new LoggingInterceptor(),        // Order 20
            new MetricsInterceptor()         // Order 30
        );
    }
}
```

### 3. Register Interceptor Provider

Create a file at `src/main/resources/META-INF/services/org.opensearch.transport.grpc.spi.GrpcInterceptorProvider`:

```
com.example.plugin.MyInterceptorProvider
```

### 4. Advanced Interceptor Patterns

#### Non-Critical Interceptor (Graceful Degradation)

```java
public class MetricsInterceptor implements OrderedGrpcInterceptor {

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public ServerInterceptor getInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next
            ) {
                // Wrap the call to intercept responses
                return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                    @Override
                    public void sendMessage(RespT message) {
                        try {
                            // Collect metrics
                            collectMetrics(message);
                            super.sendMessage(message);
                        } catch (Exception e) {
                            // Non-critical failure - log but don't break the request
                            logger.warn("Metrics collection failed", e);
                            super.sendMessage(message);
                        }
                    }
                }, headers);
            }
        };
    }

    @Override
    public boolean isIgnoreFailure() {
        return true; // Non-critical - failures don't break the chain
    }
}
```

#### Response Processing Interceptor

```java
public class SecurityFilterInterceptor implements OrderedGrpcInterceptor {

    @Override
    public int getOrder() {
        return 40;
    }

    @Override
    public ServerInterceptor getInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next
            ) {
                return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                    @Override
                    public void sendMessage(RespT message) {
                        try {
                            // Filter sensitive data from response
                            RespT filteredMessage = securityFilter.filterResponse(message);
                            super.sendMessage(filteredMessage);
                        } catch (SecurityException e) {
                            // Security filtering failed - safe to send error
                            call.close(Status.PERMISSION_DENIED.withDescription("Response filtering failed"), new Metadata());
                        }
                    }
                }, headers);
            }
        };
    }
}
```

### 5. Interceptor Ordering

Interceptors execute in order based on their `getOrder()` value:

```java
// Execution order:
// 1. AuthInterceptor (order 10)
// 2. LoggingInterceptor (order 20)
// 3. MetricsInterceptor (order 30)
// 4. SecurityFilterInterceptor (order 40)
```

### 6. Exception Handling

- **Critical Interceptors** (`isIgnoreFailure() = false`): Exceptions break the entire request chain
- **Non-Critical Interceptors** (`isIgnoreFailure() = true`): Exceptions are logged but don't break the chain

### 7. Testing Interceptors

```java
@Test
public void testAuthInterceptor() {
    AuthInterceptor interceptor = new AuthInterceptor();

    // Test with valid token
    Metadata headers = new Metadata();
    headers.put(AUTH_TOKEN_KEY, "valid-token");

    ServerCall.Listener<String> result = interceptor.getInterceptor()
        .interceptCall(mockCall, headers, mockHandler);

    assertNotNull(result);
    verify(mockCall, never()).close(any(Status.class), any(Metadata.class));
}

@Test
public void testAuthInterceptorWithInvalidToken() {
    AuthInterceptor interceptor = new AuthInterceptor();

    // Test with invalid token
    Metadata headers = new Metadata();
    headers.put(AUTH_TOKEN_KEY, "invalid-token");

    interceptor.getInterceptor().interceptCall(mockCall, headers, mockHandler);

    verify(mockCall).close(
        argThat(status -> status.getCode() == Status.Code.UNAUTHENTICATED),
        eq(headers)
    );
}
```

### 8. Real-World Example: Security Plugin

```java
public class SecurityInterceptorProvider implements GrpcInterceptorProvider {

    @Override
    public List<OrderedGrpcInterceptor> getOrderedGrpcInterceptors() {
        return List.of(
            new AuthenticationInterceptor(10),    // First: authenticate user
            new AuthorizationInterceptor(20),     // Second: check permissions
            new AuditLoggingInterceptor(30),      // Third: log for compliance
            new MetricsInterceptor(40)           // Fourth: collect metrics
        );
    }
}
```

**Key Benefits:**
- **Minimal Dependencies**: Only requires `grpc-api` (no heavy gRPC dependencies)
- **Ordered Execution**: Interceptors execute in predictable sequence
- **Exception Safety**: Proper handling of critical vs non-critical failures
- **Response Processing**: Can intercept and modify responses
- **OpenSearch Integration**: Follows OpenSearch's SPI patterns

## Real-World Integration: Uber Security Plugin

Here's how to integrate gRPC interceptors with your existing security plugin:

### **1. Update Plugin Class Declaration**

```java
public class SecurityPlugin extends Plugin implements ActionPlugin, ExtensiblePlugin {
    // ... existing code ...
}
```

### **2. Add gRPC Interceptor Dependencies**

In your plugin's `build.gradle`:

```gradle
dependencies {
    // Existing dependencies...

    // Add gRPC interceptor SPI
    compileOnly 'org.opensearch.plugin:transport-grpc-spi:${opensearch.version}'
    compileOnly 'io.grpc:grpc-api:${versions.grpc}'
}
```

### **3. Create gRPC Security Interceptor**

```java
package com.uber.opensearch.security.grpc;

import org.opensearch.transport.grpc.spi.OrderedGrpcInterceptor;
import io.grpc.*;
import com.uber.opensearch.security.auth.Authorizer;
import com.uber.opensearch.security.metrics.M3MetricsCollector;
import com.uber.opensearch.security.util.PluginHelper;

public class GrpcSecurityInterceptor implements OrderedGrpcInterceptor {

    private final Authorizer authorizer;
    private final M3MetricsCollector metricsCollector;

    public GrpcSecurityInterceptor(Authorizer authorizer, M3MetricsCollector metricsCollector) {
        this.authorizer = authorizer;
        this.metricsCollector = metricsCollector;
    }

    @Override
    public int getOrder() {
        return 10; // Execute early for security
    }

    @Override
    public ServerInterceptor getInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next
            ) {

                try {
                    // Extract authentication information from gRPC headers
                    String uberSource = headers.get(Metadata.Key.of("x-uber-source", Metadata.ASCII_STRING_MARSHALLER));
                    String callerToken = headers.get(Metadata.Key.of("rpc-utoken-caller", Metadata.ASCII_STRING_MARSHALLER));

                    // Perform authorization (similar to your existing ActionFilter logic)
                    if (!authorizeRequest(call, uberSource, callerToken)) {
                        call.close(
                            Status.PERMISSION_DENIED.withDescription("Authorization failed"),
                            new Metadata()
                        );
                        return new ServerCall.Listener<ReqT>() {};
                    }

                    // Add security context to headers for downstream processing
                    Metadata newHeaders = new Metadata();
                    newHeaders.merge(headers);
                    newHeaders.put(Metadata.Key.of("security-context", Metadata.ASCII_STRING_MARSHALLER),
                                  "authenticated");

                    return next.startCall(call, newHeaders);

                } catch (Exception e) {
                    logger.error("Security interceptor failed", e);
                    call.close(
                        Status.INTERNAL.withDescription("Security check failed"),
                        new Metadata()
                    );
                    return new ServerCall.Listener<ReqT>() {};
                }
            }
        };
    }

    @Override
    public boolean isIgnoreFailure() {
        return false; // Critical - security must succeed
    }

    private boolean authorizeRequest(ServerCall<?, ?> call, String uberSource, String callerToken) {
        // Implement your authorization logic here
        // This should mirror the logic from your UberSecurityFilter

        if (callerToken == null || callerToken.isEmpty()) {
            return false;
        }

        // Use your existing authorizer
        // You'll need to adapt the authorization logic for gRPC context
        return authorizer.authorize(call.getMethodDescriptor().getFullMethodName(), callerToken);
    }
}
```

### **4. Create gRPC Metrics Interceptor**

```java
package com.uber.opensearch.security.grpc;

import org.opensearch.transport.grpc.spi.OrderedGrpcInterceptor;
import io.grpc.*;
import com.uber.opensearch.security.metrics.M3MetricsCollector;

public class GrpcMetricsInterceptor implements OrderedGrpcInterceptor {

    private final M3MetricsCollector metricsCollector;

    public GrpcMetricsInterceptor(M3MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    @Override
    public int getOrder() {
        return 20; // Execute after security
    }

    @Override
    public ServerInterceptor getInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next
            ) {
                long startTime = System.currentTimeMillis();

                return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                    @Override
                    public void sendMessage(RespT message) {
                        try {
                            // Collect gRPC metrics
                            long duration = System.currentTimeMillis() - startTime;
                            recordGrpcMetrics(call.getMethodDescriptor().getFullMethodName(), duration);

                            super.sendMessage(message);
                        } catch (Exception e) {
                            // Non-critical failure - log but don't break the request
                            logger.warn("gRPC metrics collection failed", e);
                            super.sendMessage(message);
                        }
                    }
                }, headers);
            }
        };
    }

    @Override
    public boolean isIgnoreFailure() {
        return true; // Non-critical - failures don't break the chain
    }

    private void recordGrpcMetrics(String method, long duration) {
        // Record metrics similar to your existing M3MetricsCollector
        metricsCollector.incrementCounter("grpc.request", Collections.emptyMap());
        metricsCollector.startHistogram("grpc.latency", M3MetricsCollector.LATENCY_BUCKETS);
    }
}
```

### **5. Create gRPC Interceptor Provider**

```java
package com.uber.opensearch.security.grpc;

import org.opensearch.transport.grpc.spi.GrpcInterceptorProvider;
import org.opensearch.transport.grpc.spi.OrderedGrpcInterceptor;
import com.uber.opensearch.security.auth.Authorizer;
import com.uber.opensearch.security.metrics.M3MetricsCollector;
import java.util.List;

public class SecurityGrpcInterceptorProvider implements GrpcInterceptorProvider {

    private final Authorizer authorizer;
    private final M3MetricsCollector metricsCollector;

    public SecurityGrpcInterceptorProvider(Authorizer authorizer, M3MetricsCollector metricsCollector) {
        this.authorizer = authorizer;
        this.metricsCollector = metricsCollector;
    }

    @Override
    public List<OrderedGrpcInterceptor> getOrderedGrpcInterceptors() {
        return List.of(
            new GrpcSecurityInterceptor(authorizer, metricsCollector),  // Order 10
            new GrpcMetricsInterceptor(metricsCollector)               // Order 20
        );
    }
}
```

### **6. Update Your SecurityPlugin Class**

```java
public class SecurityPlugin extends Plugin implements ActionPlugin, ExtensiblePlugin {

    // ... existing code ...

    @Override
    public Collection<Object> createComponents(
        Client localClient,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<RepositoriesService> repositoriesServiceSupplier) {

        // ... existing initialization code ...

        // Create gRPC interceptor provider
        SecurityGrpcInterceptorProvider grpcInterceptorProvider =
            new SecurityGrpcInterceptorProvider(this.authorizer, this.m3MetricsCollector);

        // Return both existing components and gRPC interceptor provider
        return List.of(
            uberSecurityFilter,           // Existing ActionFilter
            grpcInterceptorProvider       // New gRPC interceptor provider
        );
    }

    // ... rest of existing code ...
}
```

### **7. Create SPI Registration File**

Create `src/main/resources/META-INF/services/org.opensearch.transport.grpc.spi.GrpcInterceptorProvider`:

```
com.uber.opensearch.security.grpc.SecurityGrpcInterceptorProvider
```

### **8. Update Plugin Descriptor**

In your `plugin-descriptor.properties`:

```properties
name=security-plugin
description=Uber Security Plugin
version=1.0.0
classname=com.uber.opensearch.security.SecurityPlugin
extended.plugins=transport-grpc
```

## 🎯 **Benefits of This Integration**

1. **Unified Security**: Same authorization logic for both REST and gRPC requests
2. **Consistent Metrics**: Same M3 metrics collection for both protocols
3. **Shared Components**: Reuse existing `Authorizer` and `M3MetricsCollector`
4. **Protocol-Agnostic**: Security logic works across different transport protocols
5. **Minimal Changes**: Leverage existing security infrastructure

This approach allows your security plugin to provide the same authentication, authorization, and metrics collection for gRPC requests as it does for REST requests! 🚀
