#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
mpv_source="${1:-$task_root/build/mpv-native/mpv-android/buildscripts/deps/mpv}"
test_output="$(mktemp -d /private/tmp/webhtv-fel-contract.XXXXXX)"
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -I"$task_root/third_party/mpv-player-jni/src" \
  -I"$task_root/third_party/mpv-player-jni/include" -I"$mpv_source" \
  "$task_root/third_party/mpv-player-jni/tests/fel_contract_test.cpp" \
  -o "$test_output/fel-contract-test"
"$test_output/fel-contract-test"
# Compile the real wrapper bodies; a blocking decoder-dispatch stub makes any
# accidental synchronous read fail deterministically, without timing sleeps.
awk '
  /^static int update_cached_values\(/ || /^int mp_decoder_wrapper_control\(/ ||
  /^double mp_decoder_wrapper_get_container_fps\(/ { copying = 1 }
  copying { print }
  copying && /^}/ { copying = 0 }
' "$mpv_source/filters/f_decoder_wrapper.c" | \
  "${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined \
    -I"$mpv_source" \
    -include "$task_root/third_party/mpv-player-jni/tests/fel_decoder_control_test.cpp" \
    -x c++ - -o "$test_output/fel-decoder-control-test"
"$test_output/fel-decoder-control-test"
awk '
  /^static int preload_dropped_fel_frame\(/ || /^static bool render_frame\(/ { copying = 1 }
  copying { print }
  copying && /^}/ { copying = 0 }
' "$mpv_source/video/out/vo_gpu_next.c" "$mpv_source/video/out/vo.c" | \
  "${CC:-cc}" -std=c11 -Wall -Wextra -Werror -Wno-unused-function \
    -fsanitize=address,undefined -I"$mpv_source" \
    -include "$task_root/third_party/mpv-player-jni/tests/fel_vo_drop_test.c" \
    -x c - -o "$test_output/fel-vo-drop-test"
"$test_output/fel-vo-drop-test"
awk '
  /^static void cancel_fel_prepare\(/ || /^int vo_prepare_fel_frame\(/ ||
  /^static int64_t process_fel_prepare\(/ || /^static bool use_video_lookahead\(/ ||
  /^static int get_req_frames\(/ || /^static bool needs_new_frame\(/ ||
  /^static void add_new_frame\(/ || /^static bool have_new_frame\(/ ||
  /^static void trace_fel_core\(/ || /^static int video_output_image\(/ ||
  /^static bool output_has_live_fel_frame\(/ || /^static int select_reusable_output\(/ ||
  /^static void restore_fel_display_params\(/ || /^static bool hwdec_reconfig\(/ { copying = 1 }
  copying { print }
  copying && /^}/ { copying = 0 }
' "$mpv_source/video/out/vo.c" "$mpv_source/player/video.c" \
  "$mpv_source/video/out/hwdec/hwdec_aimagereader_vk_stable.c" \
  "$mpv_source/video/out/vo_gpu_next.c" | \
  "${CC:-cc}" -std=c11 -Wall -Wextra -Werror -Wno-unused-function \
    -fsanitize=address,undefined -I"$mpv_source" \
    $(pkg-config --cflags libavutil) \
    -include "$task_root/third_party/mpv-player-jni/tests/fel_core_preload_test.c" \
    -x c - $(pkg-config --libs libavutil) -o "$test_output/fel-core-preload-test"
"$test_output/fel-core-preload-test"
awk '
  /^static bool stage_fel_before_publish\(/ || /^static bool finish_output\(/ ||
  /^bool aimagereader_vk_stable_reuse\(/ { copying = 1 }
  copying { print }
  copying && /^}/ { copying = 0 }
' "$mpv_source/filters/f_decoder_wrapper.c" \
  "$mpv_source/video/out/hwdec/hwdec_aimagereader_vk_stable.c" | \
  "${CC:-cc}" -std=c11 -Wall -Wextra -Werror -Wno-unused-function \
    -fsanitize=address,undefined -I"$mpv_source" \
    $(pkg-config --cflags libavutil) \
    -include "$task_root/third_party/mpv-player-jni/tests/fel_producer_handoff_test.c" \
    -x c - $(pkg-config --libs libavutil) -o "$test_output/fel-producer-handoff-test"
"$test_output/fel-producer-handoff-test"
awk '
  /^static void mp_image_destructor\(/ || /^void mp_image_unref_data\(/ ||
  /^static void ref_buffer\(/ || /^struct mp_image \*mp_image_new_ref\(/ ||
  /^struct mp_image \*mp_image_new_dummy_ref\(/ { copying = 1 }
  copying { print }
  copying && /^}/ { copying = 0 }
' "$mpv_source/video/mp_image.c" | \
  "${CC:-cc}" -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
    $(pkg-config --cflags libavutil) \
    -include "$task_root/third_party/mpv-player-jni/tests/fel_image_lease_test.c" \
    -x c - $(pkg-config --libs libavutil) -o "$test_output/fel-image-lease-test"
"$test_output/fel-image-lease-test"
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -pthread -I"$mpv_source" \
  "$task_root/third_party/mpv-player-jni/tests/fel_progress_test.c" \
  -o "$test_output/fel-progress-test"
"$test_output/fel-progress-test"
# Exercise the production bitstream/ownership helper with a real FFmpeg BSF.
# Optional argument 2 is a length-prefixed Profile 7 sample (not a fixture copy).
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -I"$mpv_source" $(pkg-config --cflags libavformat libavcodec libavutil) \
  "$task_root/third_party/mpv-player-jni/tests/fel_packet_test.c" \
  $(pkg-config --libs libavformat libavcodec libavutil) \
  -o "$test_output/fel-packet-test"
"$test_output/fel-packet-test" ${2:+"$2"}
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
  $(pkg-config --cflags libavformat libavcodec libavutil) \
  "$task_root/third_party/mpv-player-jni/tests/fel_el_rpu_test.c" \
  $(pkg-config --libs libavformat libavcodec libavutil) \
  -o "$test_output/fel-el-rpu-test"
"$test_output/fel-el-rpu-test" ${2:+"$2"}
awk '
  /^static void inherit_dovi_from_el\(/ { copying = 1 }
  copying { print }
  copying && /^}/ { copying = 0 }
' "$mpv_source/filters/f_enhancement_pair.c" | \
  "${CC:-cc}" -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
    $(pkg-config --cflags libavutil) \
    -include "$task_root/third_party/mpv-player-jni/tests/fel_rpu_inherit_test.c" \
    -x c - $(pkg-config --libs libavutil) -o "$test_output/fel-rpu-inherit-test"
"$test_output/fel-rpu-inherit-test"
python3 "$task_root/scripts/verify_mpv_fel_contract.py" --mpv-source "$mpv_source"
rg -q 'mp_android_fel_stage_output\(' "$mpv_source/video/out/hwdec/hwdec_aimagereader.c"
rg -q 'mp_android_fel_preserve_depth\(' "$mpv_source/video/out/hwdec/hwdec_aimagereader_vk_stable.c"
rg -q 'finish_output\(p, output, MP_ANDROID_FEL_COPY_WAIT_NS\)' "$mpv_source/video/out/hwdec/hwdec_aimagereader_vk_stable.c"
