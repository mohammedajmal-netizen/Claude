package app.truelog.camera;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One selectable lens. Samsung exposes lenses differently by firmware, so this is found at runtime. */
final class Lens {
    String cameraId;
    String physicalId;     // set when reached through a logical camera
    CameraCharacteristics chars;
    float focalMm, equivMm;
    boolean front, hasRaw, inPublicList;
    String label = "";

    CameraCharacteristics characteristics() {
        return chars;
    }

    String key() {
        return cameraId + (physicalId != null ? "/" + physicalId : "");
    }

    Size[] rawSizes() {
        StreamConfigurationMap m = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] s = m == null ? null : m.getOutputSizes(ImageFormat.RAW_SENSOR);
        return s == null ? new Size[0] : s;
    }

    static boolean has(CameraCharacteristics c, int capability) {
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps == null) return false;
        for (int v : caps) if (v == capability) return true;
        return false;
    }

    private static Lens make(String id, String physical, CameraCharacteristics c, boolean listed) {
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (facing == null || focals == null || focals.length == 0 || sensor == null) return null;
        if (facing == CameraMetadata.LENS_FACING_EXTERNAL) return null;
        if (!has(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) return null;
        Lens l = new Lens();
        l.cameraId = id;
        l.physicalId = physical;
        l.chars = c;
        l.focalMm = focals[0];
        double diag = Math.hypot(sensor.getWidth(), sensor.getHeight());
        l.equivMm = (float) (focals[0] * 43.27 / diag);
        l.front = facing == CameraMetadata.LENS_FACING_FRONT;
        l.hasRaw = has(c, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW);
        l.inPublicList = listed;
        return l;
    }

    /** Lens choice order: publicly listed, then able to stream RAW, then opened directly. */
    private static int score(Lens l) {
        return (l.inPublicList ? 4 : 0) + (l.hasRaw ? 2 : 0) + (l.physicalId == null ? 1 : 0);
    }

    static List<Lens> scan(CameraManager manager) {
        List<Lens> found = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>();
        try {
            listed.addAll(Arrays.asList(manager.getCameraIdList()));
        } catch (Exception ignored) {
            return found;
        }
        Set<String> ids = new LinkedHashSet<>(listed);
        // Some Samsung firmware hides working lenses from the list but still answers by id.
        for (int i = 0; i < 100; i++) ids.add(Integer.toString(i));

        for (String id : ids) {
            CameraCharacteristics c;
            try {
                c = manager.getCameraCharacteristics(id);
            } catch (Exception notACamera) {
                continue;
            }
            Lens l = make(id, null, c, listed.contains(id));
            if (l != null) found.add(l);
            for (String phys : c.getPhysicalCameraIds()) {
                try {
                    Lens p = make(id, phys, manager.getCameraCharacteristics(phys), listed.contains(id));
                    if (p != null) found.add(p);
                } catch (Exception ignored) {
                    // A physical camera that cannot be described cannot be offered.
                }
            }
        }

        // Several ids can reach the same glass. Keep the best route per facing and focal length.
        List<Lens> unique = new ArrayList<>();
        for (Lens l : found) {
            Lens match = null;
            for (Lens u : unique) {
                if (u.front == l.front && Math.abs(u.equivMm - l.equivMm) / u.equivMm < 0.12f) match = u;
            }
            if (match == null) {
                unique.add(l);
            } else if (score(l) > score(match)) {
                unique.set(unique.indexOf(match), l);
            }
        }
        unique.sort(Comparator.<Lens, Boolean>comparing(l -> l.front).thenComparing(l -> l.equivMm));

        // 1x is the rear lens with the largest sensor, which is what the phone itself calls 1x.
        Lens main = null;
        double mainArea = -1;
        for (Lens l : unique) {
            SizeF s = l.chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            double area = s.getWidth() * s.getHeight();
            if (!l.front && area > mainArea) {
                main = l;
                mainArea = area;
            }
        }
        for (Lens l : unique) {
            if (l.front) {
                l.label = "F";
            } else if (main != null) {
                float r = l.equivMm / main.equivMm;
                l.label = r < 0.95f ? String.format(Locale.US, ".%d", Math.round(r * 10f))
                        : Integer.toString(Math.round(r));
            }
        }
        return unique;
    }
}
