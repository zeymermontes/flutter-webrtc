#import "FlutterRTCVideoRenderer.h"

#import <AVFoundation/AVFoundation.h>
#import <CoreGraphics/CGImage.h>
#import <WebRTC/RTCCVPixelBuffer.h>
#import <WebRTC/RTCYUVHelper.h>
#import <WebRTC/RTCYUVPlanarBuffer.h>
#import <WebRTC/WebRTC.h>

#import <objc/runtime.h>

#import "FlutterWebRTCPlugin.h"
#import <os/lock.h>

@implementation FlutterRTCVideoRenderer {
  CGSize _frameSize;
  CGSize _renderSize;
  CVPixelBufferRef _pixelBufferRef;
  RTCVideoRotation _rotation;
  FlutterEventChannel* _eventChannel;
  bool _isFirstFrameRendered;
  bool _frameAvailable;
  os_unfair_lock _lock;
  CVPixelBufferPoolRef _pool;
  int _poolWidth;
  int _poolHeight;
}

@synthesize textureId = _textureId;
@synthesize registry = _registry;
@synthesize eventSink = _eventSink;
@synthesize videoTrack = _videoTrack;

- (instancetype)initWithTextureRegistry:(id<FlutterTextureRegistry>)registry
                              messenger:(NSObject<FlutterBinaryMessenger>*)messenger {
  self = [super init];
  if (self) {
    _lock = OS_UNFAIR_LOCK_INIT;
    _isFirstFrameRendered = false;
    _frameAvailable = false;
    _frameSize = CGSizeZero;
    _renderSize = CGSizeZero;
    _rotation = -1;
    _registry = registry;
    _pixelBufferRef = nil;
    _eventSink = nil;
    _rotation = -1;
    _textureId = [registry registerTexture:self];
    /*Create Event Channel.*/
    _eventChannel = [FlutterEventChannel
        eventChannelWithName:[NSString stringWithFormat:@"FlutterWebRTC/Texture%lld", _textureId]
             binaryMessenger:messenger];
    [_eventChannel setStreamHandler:self];
  }
  return self;
}

- (CVPixelBufferRef)copyPixelBuffer {
  CVPixelBufferRef buffer = nil;
  os_unfair_lock_lock(&_lock);
  if (_pixelBufferRef != nil && _frameAvailable) {
    buffer = CVBufferRetain(_pixelBufferRef);
    _frameAvailable = false;
  }
  os_unfair_lock_unlock(&_lock);
  return buffer;
}

- (void)dispose {
  [_eventChannel setStreamHandler:nil];
  _eventChannel = nil;
  os_unfair_lock_lock(&_lock);
  [_registry unregisterTexture:_textureId];
  _textureId = -1;
  if (_pixelBufferRef) {
    CVBufferRelease(_pixelBufferRef);
    _pixelBufferRef = nil;
  }
  _frameAvailable = false;
  os_unfair_lock_unlock(&_lock);
}

- (void)dealloc {
  if (_pixelBufferRef) {
    CVBufferRelease(_pixelBufferRef);
    _pixelBufferRef = nil;
  }
  if (_pool) {
    CVPixelBufferPoolRelease(_pool);
    _pool = nil;
  }
}

- (void)setVideoTrack:(RTCVideoTrack*)videoTrack {
  RTCVideoTrack* oldValue = self.videoTrack;
  if (oldValue != videoTrack) {
    os_unfair_lock_lock(&_lock);
    _videoTrack = videoTrack;
    os_unfair_lock_unlock(&_lock);
    _isFirstFrameRendered = false;
    if (oldValue) {
      [oldValue removeRenderer:self];
    }
    _frameSize = CGSizeZero;
    _renderSize = CGSizeZero;
    _rotation = -1;
    if (videoTrack) {
      [videoTrack addRenderer:self];
    }
  }
}

/// Whether Flutter can draw [buffer] as it is: an IOSurface (what the
/// Metal texture cache wraps without a copy) in NV12 or BGRA, the two
/// formats Flutter's external textures take on iOS.
static BOOL FlutterRTCCanShowPixelBuffer(CVPixelBufferRef buffer) {
  if (buffer == nil || CVPixelBufferGetIOSurface(buffer) == nil) {
    return NO;
  }
  const OSType format = CVPixelBufferGetPixelFormatType(buffer);
  return format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange ||
         format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange ||
         format == kCVPixelFormatType_32BGRA;
}

/// An NV12 buffer of [width] x [height] from the pool, which is made again
/// when the size changes.
- (CVPixelBufferRef)nv12BufferWithWidth:(int)width height:(int)height {
  if (_pool == nil || _poolWidth != width || _poolHeight != height) {
    if (_pool) {
      CVPixelBufferPoolRelease(_pool);
      _pool = nil;
    }
    NSDictionary* attributes = @{
      (id)kCVPixelBufferPixelFormatTypeKey : @(kCVPixelFormatType_420YpCbCr8BiPlanarFullRange),
      (id)kCVPixelBufferWidthKey : @(width),
      (id)kCVPixelBufferHeightKey : @(height),
      (id)kCVPixelBufferIOSurfacePropertiesKey : @{},
      (id)kCVPixelBufferMetalCompatibilityKey : @YES,
    };
    if (CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, (__bridge CFDictionaryRef)attributes,
                                &_pool) != kCVReturnSuccess) {
      _pool = nil;
      return nil;
    }
    _poolWidth = width;
    _poolHeight = height;
  }
  CVPixelBufferRef buffer = nil;
  CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, _pool, &buffer);
  return buffer;
}

/// The frame's picture as Flutter will draw it, not rotated (Flutter turns
/// it, see `textureRotation`). The decoder's or the camera's own buffer when
/// Flutter can take it as it is (no copy at all); otherwise one pass to
/// NV12 (a software decoder's I420, or a buffer that has to be cropped).
- (CVPixelBufferRef)pixelBufferForFrame:(RTCVideoFrame*)frame {
  id<RTCVideoFrameBuffer> source = frame.buffer;
  if ([source isKindOfClass:[RTCCVPixelBuffer class]]) {
    RTCCVPixelBuffer* cv = (RTCCVPixelBuffer*)source;
    if (![cv requiresCropping] && FlutterRTCCanShowPixelBuffer(cv.pixelBuffer)) {
      return CVBufferRetain(cv.pixelBuffer);
    }
  }
  id<RTCI420Buffer> i420 = [source toI420];
  CVPixelBufferRef out = [self nv12BufferWithWidth:i420.width height:i420.height];
  if (out == nil) {
    return nil;
  }
  CVPixelBufferLockBaseAddress(out, 0);
  [RTCYUVHelper I420ToNV12:i420.dataY
                srcStrideY:i420.strideY
                      srcU:i420.dataU
                srcStrideU:i420.strideU
                      srcV:i420.dataV
                srcStrideV:i420.strideV
                      dstY:CVPixelBufferGetBaseAddressOfPlane(out, 0)
                dstStrideY:(int)CVPixelBufferGetBytesPerRowOfPlane(out, 0)
                     dstUV:CVPixelBufferGetBaseAddressOfPlane(out, 1)
               dstStrideUV:(int)CVPixelBufferGetBytesPerRowOfPlane(out, 1)
                     width:i420.width
                    height:i420.height];
  CVPixelBufferUnlockBaseAddress(out, 0);
  return out;
}

#pragma mark - RTCVideoRenderer methods
- (void)renderFrame:(RTCVideoFrame*)frame {
  if (frame == nil) {
    return;
  }
  os_unfair_lock_lock(&_lock);
  BOOL attached = _videoTrack != nil;
  os_unfair_lock_unlock(&_lock);
  if (!attached) {
    return;
  }

  // Outside the lock: Flutter's raster thread takes the last picture under
  // it and must not wait for a conversion.
  CVPixelBufferRef picture = [self pixelBufferForFrame:frame];
  if (picture == nil) {
    return;
  }
  os_unfair_lock_lock(&_lock);
  CVPixelBufferRef previous = _pixelBufferRef;
  _pixelBufferRef = picture;
  _frameAvailable = true;
  if (_textureId != -1) {
    [_registry textureFrameAvailable:_textureId];
  }
  os_unfair_lock_unlock(&_lock);
  if (previous) {
    CVBufferRelease(previous);
  }

  __weak FlutterRTCVideoRenderer* weakSelf = self;
  if (_renderSize.width != frame.width || _renderSize.height != frame.height) {
    dispatch_async(dispatch_get_main_queue(), ^{
      FlutterRTCVideoRenderer* strongSelf = weakSelf;
      if (strongSelf.eventSink) {
        strongSelf.eventSink(@{
          @"event" : @"didTextureChangeVideoSize",
          @"id" : @(strongSelf.textureId),
          @"width" : @(frame.width),
          @"height" : @(frame.height),
        });
      }
    });
    _renderSize = CGSizeMake(frame.width, frame.height);
  }

  if (frame.rotation != _rotation) {
    dispatch_async(dispatch_get_main_queue(), ^{
      FlutterRTCVideoRenderer* strongSelf = weakSelf;
      if (strongSelf.eventSink) {
        strongSelf.eventSink(@{
          @"event" : @"didTextureChangeRotation",
          @"id" : @(strongSelf.textureId),
          @"rotation" : @(frame.rotation),
          // The picture is handed over as it was decoded: the view turns it.
          @"textureRotation" : @(frame.rotation),
        });
      }
    });

    _rotation = frame.rotation;
  }

  // Notify Flutter once the first frame has been rendered. Frames arrive on a
  // decoder thread and this block runs later on the main queue, so the
  // renderer may already have been disposed and freed by the time it runs.
  // The weak reference is then nil and must not be dereferenced.
  if (!_isFirstFrameRendered) {
    dispatch_async(dispatch_get_main_queue(), ^{
      FlutterRTCVideoRenderer* strongSelf = weakSelf;
      if (strongSelf == nil || strongSelf->_isFirstFrameRendered) {
        return;
      }
      if (strongSelf.eventSink) {
        strongSelf.eventSink(@{@"event" : @"didFirstFrameRendered"});
        strongSelf->_isFirstFrameRendered = true;
      }
    });
  }
}

/**
 * Sets the size of the video frame to render.
 *
 * @param size The size of the video frame to render.
 */
- (void)setSize:(CGSize)size {
  // The pictures are the frames' own buffers (or one from the pool, sized
  // by the frame): nothing to allocate here.
  _frameSize = size;
}

#pragma mark - FlutterStreamHandler methods

- (FlutterError* _Nullable)onCancelWithArguments:(id _Nullable)arguments {
  _eventSink = nil;
  return nil;
}

- (FlutterError* _Nullable)onListenWithArguments:(id _Nullable)arguments
                                       eventSink:(nonnull FlutterEventSink)sink {
  _eventSink = sink;
  return nil;
}
@end

@implementation FlutterWebRTCPlugin (FlutterVideoRendererManager)

- (FlutterRTCVideoRenderer*)createWithTextureRegistry:(id<FlutterTextureRegistry>)registry
                                            messenger:(NSObject<FlutterBinaryMessenger>*)messenger {
  return [[FlutterRTCVideoRenderer alloc] initWithTextureRegistry:registry messenger:messenger];
}

- (void)rendererSetSrcObject:(FlutterRTCVideoRenderer*)renderer stream:(RTCVideoTrack*)videoTrack {
  renderer.videoTrack = videoTrack;
}
@end
