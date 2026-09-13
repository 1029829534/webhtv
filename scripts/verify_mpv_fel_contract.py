#!/usr/bin/env python3
"""Static FEL safety contract checks; these do not replace device playback tests."""

import argparse
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
PATCH = ROOT / "third_party/patches/mpv-android-fel.patch"


def require(condition, message):
    if not condition:
        raise SystemExit(f"FAIL: {message}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mpv-source", type=Path)
    args = parser.parse_args()
    patch = PATCH.read_text()
    old_gate = (ROOT / "third_party/patches/mpv-android-dovi-el-surface.patch").read_text()
    require("+#if !HAVE_ANDROID\n+            VO_CAP_GPU_DOVI_EL |" in old_gate,
            "the default Android single-Surface gate must remain")
    additions = "\n".join(line[1:] for line in patch.splitlines()
                          if line.startswith("+") and not line.startswith("+++"))
    for marker in (
        '"android-dovi-fel", OPT_BOOL(android_dovi_fel)',
        "mpctx->opts->vo->android_dovi_fel && el",
        "VO_CAP_GPU_DOVI_EL_SW",
        "track->stream->codec->dv_profile == 7",
        "info->force_swdec = true",
        ".force_swdec = sinfo->force_swdec",
        "AV_CODEC_CAP_HARDWARE | AV_CODEC_CAP_HYBRID",
        "!ctx->force_swdec &&",
        "ctx->hwdec_opts->software_fallback == INT_MAX",
    ):
        require(marker in additions, f"missing FEL safety contract: {marker}")
    require(not re.search(r"android_dovi_fel\s*=\s*(?:true|1)\b", additions),
            "FEL must never become a native default")
    build_script = (ROOT / "scripts/build_mpv_native.sh").read_text()
    require('apply --recount "$MPV_ANDROID_FEL_PATCH"' in build_script,
            "the production native build must apply the FEL patch")

    if args.mpv_source:
        source = args.mpv_source.resolve()
        subprocess.run(["git", "-C", str(source), "apply", "--reverse", "--check",
                        "--recount", str(PATCH)], check=True)
        pair = (source / "filters/f_enhancement_pair.c").read_text()
        decoder = (source / "video/decode/vd_lavc.c").read_text()
        renderer = (source / "video/out/vo_gpu_next.c").read_text()
        wrapper = (source / "filters/f_decoder_wrapper.c").read_text()
        vo = (source / "video/out/vo.c").read_text()
        stable = (source / "video/out/hwdec/hwdec_aimagereader_vk_stable.c").read_text()
        output = wrapper[wrapper.index("output_frame:\n", wrapper.index("static void read_frame(")):]
        require(output.index("stage_fel_before_publish(p, frame)") < output.index("mp_pin_in_write(pin, frame)"),
                "FEL BL must return its source before publishing to the decoder queue")
        require("mp_android_fel_staging_ready(image->android_fel_staging->data)" in wrapper,
                "a texture handle alone is not safe source handoff")
        handoff = wrapper[wrapper.index("static int stage_fel_before_publish("):
                          wrapper.index("static void read_frame(")]
        require("mp_sleep" not in handoff and "mp_dispatch_queue_process" not in handoff,
                "pending FEL must return to filter dispatch, not wait on a live stack frame")
        require("p->fel_stage_frame = frame;" in output
                and "p->fel_stage_frame.type ? 0.002 : INFINITY" in wrapper
                and "mp_filter_mark_async_progress(p->decf)" in wrapper,
                "pending frame ownership and interruptible decoder scheduling must be connected")
        require(re.search(r"if \(p->fel_stage_failed\)\s*return;", wrapper)
                and "if (!p->decoder || p->fel_stage_failed)" not in wrapper,
                "FEL failure must not repeatedly enqueue zero-weight EOF signals")
        require("reset_fel_staging(p);\n        mp_filter_reset(p->dec_root_filter);" in wrapper
                and "reset_fel_staging(p);\n        p->request_terminate_dec_thread = 1;" in wrapper,
                "reset/stop must cancel the pending lease before resetting/terminating decode")
        stable_map = stable[stable.index("int aimagereader_vk_stable_map("):]
        require("mp_android_fel_staging_begin_init(" in stable_map
                and "mp_android_fel_staging_end_init(" in stable_map
                and stable_map.index("mp_android_fel_staging_begin_init(")
                    < stable_map.index("input = create_input(")
                    < stable_map.index("mp_android_fel_staging_end_init(")
                and "MP_ANDROID_FEL_INIT_TIMEOUT_NS" in vo
                and "MP_ANDROID_FEL_FRAME_TIMEOUT_NS" in vo,
                "only mapper-declared cold initialization can use the separate deadline")
        require(vo.index("if (same_request)") < vo.index("} else if (image->android_fel_prepared"),
                "the prepared fast path must acknowledge its own single-slot request")
        require(re.search(r"if \(result == VO_TRUE &&\s*"
                          r"!mp_android_fel_staging_ready\(image->android_fel_staging->data\)\)\s*"
                          r"result = VO_FALSE;", vo),
                "mapped-but-unreturned sources must retain the pending request")
        release = stable[stable.index("static bool release_fel_source_async("):
                         stable.index("static bool has_ycbcr_conversion(")]
        require(release.index("AImage_deleteAsync(") < release.index("mp_android_fel_staging_release_fenced("),
                "publish fence-backed completion only after Android owns the image/fd")
        require("{output->ready, output->source_release}" in stable
                and "VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT" in stable
                and "p->android_fel && p->release_sync_fd &&" in stable,
                "FEL source-fence export must be capability gated and independent of rendering")
        require("#define PTS_MATCH_TOLERANCE 1e-6" in pair and "#define QUEUE_MAX 16" in pair,
                "keep the upstream bounded PTS matching contract")
        require("p->el_eof" in pair and "static void pair_reset" in pair,
                "pairing must still handle EOF and seek/reset")
        require("frame->enhancement_layer = &fp->el_frame" in renderer
                and "upload_planes_sw(vo, gpu, el" in renderer,
                "gpu-next must compile the software EL upload path")
        require(re.search(r"else if \(!ctx->force_swdec &&\s*"
                          r"ctx->hwdec_opts->software_fallback == INT_MAX\)", decoder),
                "disabled BL fallback must not reject explicitly software EL")
        require("while (!ctx->avctx);" not in decoder,
                "decoder failure must honor forced EOF instead of retrying forever")
        require("#if !HAVE_ANDROID\n            VO_CAP_GPU_DOVI_EL |" in renderer,
                "ordinary Android playback must not acquire hardware EL capability")
    print("PASS: opt-in FEL gate, software-only EL, BL fallback isolation, and build reachability")


if __name__ == "__main__":
    main()
