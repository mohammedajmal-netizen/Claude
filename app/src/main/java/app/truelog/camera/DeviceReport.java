package app.truelog.camera;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Size;

import java.util.List;
import java.util.Locale;

/** Plain text report of what this phone exposes, for checking and bug reports. */
final class DeviceReport {
    private DeviceReport() {}

    static String build(List<Lens> lenses) {
        StringBuilder b = new StringBuilder();
        b.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                .append(Build.DISPLAY).append("\n\n");

        for (Lens l : lenses) {
            CameraCharacteristics c = l.chars;
            b.append(String.format(Locale.US, "LENS %s  id %s  %.1fmm (~%.0fmm eq)\n",
                    l.label, l.key(), l.focalMm, l.equivMm));
            b.append("  hw level ").append(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                    .append(", public id ").append(l.inPublicList)
                    .append(", RAW ").append(l.hasRaw)
                    .append(", manual ").append(Lens.has(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
                    .append(", logical ").append(Lens.has(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA))
                    .append(", ultra-high-res ").append(Lens.has(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR))
                    .append('\n');
            b.append("  timestamp source ").append(c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
                    .append(", CFA ").append(c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT))
                    .append(", white ").append(c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL))
                    .append(", shading in RAW ").append(c.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED))
                    .append(", colour matrix ").append(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null)
                    .append(", forward matrix ").append(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) != null)
                    .append('\n');
            b.append("  ISO ").append(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE))
                    .append(", exposure ns ").append(c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE))
                    .append(", zoom ").append(c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE))
                    .append('\n');
            DynamicRangeProfiles d = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
            b.append("  dynamic range profiles ").append(d == null ? "none" : d.getSupportedProfiles()).append('\n');

            StreamConfigurationMap m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            b.append("  RAW_SENSOR:");
            for (Size s : l.rawSizes()) {
                long ns = m.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, s);
                b.append(String.format(Locale.US, " %dx%d@%.1f", s.getWidth(), s.getHeight(), ns > 0 ? 1e9 / ns : 0));
            }
            b.append('\n');
            Size[] raw10 = m.getOutputSizes(ImageFormat.RAW10);
            b.append("  RAW10 sizes: ").append(raw10 == null ? 0 : raw10.length).append("\n\n");
        }

        b.append("HEVC ENCODERS\n");
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            MediaCodecInfo.CodecCapabilities caps;
            try {
                caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            } catch (IllegalArgumentException e) {
                continue;
            }
            boolean main10 = false, p010 = false;
            for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) main10 = true;
            }
            for (int f : caps.colorFormats) {
                if (f == AvRecorder.COLOR_P010) p010 = true;
            }
            MediaCodecInfo.VideoCapabilities v = caps.getVideoCapabilities();
            b.append("  ").append(info.getName())
                    .append(info.isHardwareAccelerated() ? " hw" : " sw")
                    .append(", Main10 ").append(main10)
                    .append(", P010 ").append(p010)
                    .append(", 4K30 ").append(v.areSizeAndRateSupported(3840, 2160, 30))
                    .append(", 4K60 ").append(v.areSizeAndRateSupported(3840, 2160, 60))
                    .append(", max ").append(v.getBitrateRange().getUpper() / 1_000_000).append(" Mb/s\n");
        }
        return b.toString();
    }
}
