package phone_center;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * 手机数据中心服务
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.60.0)",
    comments = "Source: phone_center.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class PhoneCenterGrpc {

  private PhoneCenterGrpc() {}

  public static final java.lang.String SERVICE_NAME = "phone_center.PhoneCenter";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<phone_center.PhoneCenterOuterClass.GetFastSwitchJsonRequest,
      phone_center.PhoneCenterOuterClass.GetFastSwitchJsonResponse> getGetFastSwitchJsonMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetFastSwitchJson",
      requestType = phone_center.PhoneCenterOuterClass.GetFastSwitchJsonRequest.class,
      responseType = phone_center.PhoneCenterOuterClass.GetFastSwitchJsonResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<phone_center.PhoneCenterOuterClass.GetFastSwitchJsonRequest,
      phone_center.PhoneCenterOuterClass.GetFastSwitchJsonResponse> getGetFastSwitchJsonMethod() {
    io.grpc.MethodDescriptor<phone_center.PhoneCenterOuterClass.GetFastSwitchJsonRequest, phone_center.PhoneCenterOuterClass.GetFastSwitchJsonResponse> getGetFastSwitchJsonMethod;
    if ((getGetFastSwitchJsonMethod = PhoneCenterGrpc.getGetFastSwitchJsonMethod) == null) {
      synchronized (PhoneCenterGrpc.class) {
        if ((getGetFastSwitchJsonMethod = PhoneCenterGrpc.getGetFastSwitchJsonMethod) == null) {
          PhoneCenterGrpc.getGetFastSwitchJsonMethod = getGetFastSwitchJsonMethod =
              io.grpc.MethodDescriptor.<phone_center.PhoneCenterOuterClass.GetFastSwitchJsonRequest, phone_center.PhoneCenterOuterClass.GetFastSwitchJsonResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetFastSwitchJson"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  phone_center.PhoneCenterOuterClass.GetFastSwitchJsonRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  phone_center.PhoneCenterOuterClass.GetFastSwitchJsonResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PhoneCenterMethodDescriptorSupplier("GetFastSwitchJson"))
              .build();
        }
      }
    }
    return getGetFastSwitchJsonMethod;
  }

  private static volatile io.grpc.MethodDescriptor<phone_center.PhoneCenterOuterClass.GetInstallAppsRequest,
      phone_center.PhoneCenterOuterClass.GetInstallAppsResponse> getGetInstallAppsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetInstallApps",
      requestType = phone_center.PhoneCenterOuterClass.GetInstallAppsRequest.class,
      responseType = phone_center.PhoneCenterOuterClass.GetInstallAppsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<phone_center.PhoneCenterOuterClass.GetInstallAppsRequest,
      phone_center.PhoneCenterOuterClass.GetInstallAppsResponse> getGetInstallAppsMethod() {
    io.grpc.MethodDescriptor<phone_center.PhoneCenterOuterClass.GetInstallAppsRequest, phone_center.PhoneCenterOuterClass.GetInstallAppsResponse> getGetInstallAppsMethod;
    if ((getGetInstallAppsMethod = PhoneCenterGrpc.getGetInstallAppsMethod) == null) {
      synchronized (PhoneCenterGrpc.class) {
        if ((getGetInstallAppsMethod = PhoneCenterGrpc.getGetInstallAppsMethod) == null) {
          PhoneCenterGrpc.getGetInstallAppsMethod = getGetInstallAppsMethod =
              io.grpc.MethodDescriptor.<phone_center.PhoneCenterOuterClass.GetInstallAppsRequest, phone_center.PhoneCenterOuterClass.GetInstallAppsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetInstallApps"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  phone_center.PhoneCenterOuterClass.GetInstallAppsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  phone_center.PhoneCenterOuterClass.GetInstallAppsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PhoneCenterMethodDescriptorSupplier("GetInstallApps"))
              .build();
        }
      }
    }
    return getGetInstallAppsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoRequest,
      phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoResponse> getGetPhoneJsonByCpiUserInfoMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetPhoneJsonByCpiUserInfo",
      requestType = phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoRequest.class,
      responseType = phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoRequest,
      phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoResponse> getGetPhoneJsonByCpiUserInfoMethod() {
    io.grpc.MethodDescriptor<phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoRequest, phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoResponse> getGetPhoneJsonByCpiUserInfoMethod;
    if ((getGetPhoneJsonByCpiUserInfoMethod = PhoneCenterGrpc.getGetPhoneJsonByCpiUserInfoMethod) == null) {
      synchronized (PhoneCenterGrpc.class) {
        if ((getGetPhoneJsonByCpiUserInfoMethod = PhoneCenterGrpc.getGetPhoneJsonByCpiUserInfoMethod) == null) {
          PhoneCenterGrpc.getGetPhoneJsonByCpiUserInfoMethod = getGetPhoneJsonByCpiUserInfoMethod =
              io.grpc.MethodDescriptor.<phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoRequest, phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetPhoneJsonByCpiUserInfo"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PhoneCenterMethodDescriptorSupplier("GetPhoneJsonByCpiUserInfo"))
              .build();
        }
      }
    }
    return getGetPhoneJsonByCpiUserInfoMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static PhoneCenterStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PhoneCenterStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PhoneCenterStub>() {
        @java.lang.Override
        public PhoneCenterStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PhoneCenterStub(channel, callOptions);
        }
      };
    return PhoneCenterStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static PhoneCenterBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PhoneCenterBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PhoneCenterBlockingStub>() {
        @java.lang.Override
        public PhoneCenterBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PhoneCenterBlockingStub(channel, callOptions);
        }
      };
    return PhoneCenterBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static PhoneCenterFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PhoneCenterFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PhoneCenterFutureStub>() {
        @java.lang.Override
        public PhoneCenterFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PhoneCenterFutureStub(channel, callOptions);
        }
      };
    return PhoneCenterFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * 手机数据中心服务
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * 获取快速换机JSON
     * </pre>
     */
    default void getFastSwitchJson(phone_center.PhoneCenterOuterClass.GetFastSwitchJsonRequest request,
        io.grpc.stub.StreamObserver<phone_center.PhoneCenterOuterClass.GetFastSwitchJsonResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetFastSwitchJsonMethod(), responseObserver);
    }

    /**
     */
    default void getInstallApps(phone_center.PhoneCenterOuterClass.GetInstallAppsRequest request,
        io.grpc.stub.StreamObserver<phone_center.PhoneCenterOuterClass.GetInstallAppsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetInstallAppsMethod(), responseObserver);
    }

    /**
     */
    default void getPhoneJsonByCpiUserInfo(phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoRequest request,
        io.grpc.stub.StreamObserver<phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetPhoneJsonByCpiUserInfoMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service PhoneCenter.
   * <pre>
   * 手机数据中心服务
   * </pre>
   */
  public static abstract class PhoneCenterImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return PhoneCenterGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service PhoneCenter.
   * <pre>
   * 手机数据中心服务
   * </pre>
   */
  public static final class PhoneCenterStub
      extends io.grpc.stub.AbstractAsyncStub<PhoneCenterStub> {
    private PhoneCenterStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PhoneCenterStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PhoneCenterStub(channel, callOptions);
    }

    /**
     * <pre>
     * 获取快速换机JSON
     * </pre>
     */
    public void getFastSwitchJson(phone_center.PhoneCenterOuterClass.GetFastSwitchJsonRequest request,
        io.grpc.stub.StreamObserver<phone_center.PhoneCenterOuterClass.GetFastSwitchJsonResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetFastSwitchJsonMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getInstallApps(phone_center.PhoneCenterOuterClass.GetInstallAppsRequest request,
        io.grpc.stub.StreamObserver<phone_center.PhoneCenterOuterClass.GetInstallAppsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetInstallAppsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getPhoneJsonByCpiUserInfo(phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoRequest request,
        io.grpc.stub.StreamObserver<phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetPhoneJsonByCpiUserInfoMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service PhoneCenter.
   * <pre>
   * 手机数据中心服务
   * </pre>
   */
  public static final class PhoneCenterBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<PhoneCenterBlockingStub> {
    private PhoneCenterBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PhoneCenterBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PhoneCenterBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * 获取快速换机JSON
     * </pre>
     */
    public phone_center.PhoneCenterOuterClass.GetFastSwitchJsonResponse getFastSwitchJson(phone_center.PhoneCenterOuterClass.GetFastSwitchJsonRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetFastSwitchJsonMethod(), getCallOptions(), request);
    }

    /**
     */
    public phone_center.PhoneCenterOuterClass.GetInstallAppsResponse getInstallApps(phone_center.PhoneCenterOuterClass.GetInstallAppsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetInstallAppsMethod(), getCallOptions(), request);
    }

    /**
     */
    public phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoResponse getPhoneJsonByCpiUserInfo(phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetPhoneJsonByCpiUserInfoMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service PhoneCenter.
   * <pre>
   * 手机数据中心服务
   * </pre>
   */
  public static final class PhoneCenterFutureStub
      extends io.grpc.stub.AbstractFutureStub<PhoneCenterFutureStub> {
    private PhoneCenterFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PhoneCenterFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PhoneCenterFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * 获取快速换机JSON
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<phone_center.PhoneCenterOuterClass.GetFastSwitchJsonResponse> getFastSwitchJson(
        phone_center.PhoneCenterOuterClass.GetFastSwitchJsonRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetFastSwitchJsonMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<phone_center.PhoneCenterOuterClass.GetInstallAppsResponse> getInstallApps(
        phone_center.PhoneCenterOuterClass.GetInstallAppsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetInstallAppsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoResponse> getPhoneJsonByCpiUserInfo(
        phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetPhoneJsonByCpiUserInfoMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_FAST_SWITCH_JSON = 0;
  private static final int METHODID_GET_INSTALL_APPS = 1;
  private static final int METHODID_GET_PHONE_JSON_BY_CPI_USER_INFO = 2;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_FAST_SWITCH_JSON:
          serviceImpl.getFastSwitchJson((phone_center.PhoneCenterOuterClass.GetFastSwitchJsonRequest) request,
              (io.grpc.stub.StreamObserver<phone_center.PhoneCenterOuterClass.GetFastSwitchJsonResponse>) responseObserver);
          break;
        case METHODID_GET_INSTALL_APPS:
          serviceImpl.getInstallApps((phone_center.PhoneCenterOuterClass.GetInstallAppsRequest) request,
              (io.grpc.stub.StreamObserver<phone_center.PhoneCenterOuterClass.GetInstallAppsResponse>) responseObserver);
          break;
        case METHODID_GET_PHONE_JSON_BY_CPI_USER_INFO:
          serviceImpl.getPhoneJsonByCpiUserInfo((phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoRequest) request,
              (io.grpc.stub.StreamObserver<phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getGetFastSwitchJsonMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              phone_center.PhoneCenterOuterClass.GetFastSwitchJsonRequest,
              phone_center.PhoneCenterOuterClass.GetFastSwitchJsonResponse>(
                service, METHODID_GET_FAST_SWITCH_JSON)))
        .addMethod(
          getGetInstallAppsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              phone_center.PhoneCenterOuterClass.GetInstallAppsRequest,
              phone_center.PhoneCenterOuterClass.GetInstallAppsResponse>(
                service, METHODID_GET_INSTALL_APPS)))
        .addMethod(
          getGetPhoneJsonByCpiUserInfoMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoRequest,
              phone_center.PhoneCenterOuterClass.GetPhoneJsonByCpiUserInfoResponse>(
                service, METHODID_GET_PHONE_JSON_BY_CPI_USER_INFO)))
        .build();
  }

  private static abstract class PhoneCenterBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    PhoneCenterBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return phone_center.PhoneCenterOuterClass.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("PhoneCenter");
    }
  }

  private static final class PhoneCenterFileDescriptorSupplier
      extends PhoneCenterBaseDescriptorSupplier {
    PhoneCenterFileDescriptorSupplier() {}
  }

  private static final class PhoneCenterMethodDescriptorSupplier
      extends PhoneCenterBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    PhoneCenterMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (PhoneCenterGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new PhoneCenterFileDescriptorSupplier())
              .addMethod(getGetFastSwitchJsonMethod())
              .addMethod(getGetInstallAppsMethod())
              .addMethod(getGetPhoneJsonByCpiUserInfoMethod())
              .build();
        }
      }
    }
    return result;
  }
}
