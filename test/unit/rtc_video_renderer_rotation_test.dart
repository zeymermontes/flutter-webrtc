import 'package:flutter_test/flutter_test.dart';

import 'package:flutter_webrtc/src/native/rtc_video_renderer_impl.dart';

void main() {
  group('texture rotation left to the view', () {
    test('a rotation the platform did not apply turns the texture', () {
      final renderer = RTCVideoRenderer();
      renderer.eventListener({
        'event': 'didTextureChangeRotation',
        'rotation': 90,
        'textureRotation': 90,
      });
      expect(renderer.value.rotation, 90);
      expect(renderer.textureQuarterTurns, 1);

      renderer.eventListener({
        'event': 'didTextureChangeRotation',
        'rotation': 270,
        'textureRotation': 270,
      });
      expect(renderer.textureQuarterTurns, 3);

      renderer.eventListener({
        'event': 'didTextureChangeRotation',
        'rotation': 0,
        'textureRotation': 0,
      });
      expect(renderer.textureQuarterTurns, 0);
    });

    test('a platform that turns the picture itself leaves it as it is', () {
      final renderer = RTCVideoRenderer();
      renderer.eventListener({
        'event': 'didTextureChangeRotation',
        'rotation': 90,
      });
      expect(renderer.value.rotation, 90);
      expect(renderer.textureQuarterTurns, 0);
    });
  });
}
