// Compile actual producer handoff + GPU completion/cache-hit function bodies.
// The device has one available output; this is a contract model, not a claim
// about the tested TV's measured hardware DPB capacity or GPU performance.
#include "filters/f_android_fel.h"
#include <libavutil/buffer.h>
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#define MP_TIME_MS_TO_NS(ms) ((ms) * 1000000LL)
#define MPMAX(a, b) ((a) > (b) ? (a) : (b))
#define MP_INFO(...) ((void)0)
#define MP_ERR(...) ((void)0)
#define mp_warn(...) ((void)0)
#define POOL_LOG_INTERVAL 120
enum { MP_FRAME_VIDEO = 1, MP_FRAME_EOF = 2 };
enum { IMGFMT_MEDIACODEC = 1, IMGFMT_YUV420P10 = 2 };
enum { VO_ERROR = -1, VO_NOTIMPL = -3, VO_FALSE = 0, VO_TRUE = 1 };
enum { VK_SUCCESS = 0, VK_NOT_READY = 1, VK_TIMEOUT = 2, VK_TRUE = 1 };
typedef int VkResult;
struct fake_fence { int64_t ready_at; bool reset; };
typedef struct fake_fence *VkFence;
struct fake_image { bool returned; };
struct mp_image {
    int imgfmt;
    double pts;
    void *planes[4];
    AVBufferRef *android_fel_staging;
};
struct mp_frame { int type; struct mp_image *data; };
struct vk_input { int users; bool removed; };
struct vk_output {
    VkFence fence;
    struct fake_image *source_image;
    struct mp_image *source_frame, **source_aliases;
    int num_source_aliases;
    struct vk_input *input;
    bool pending;
    void *ratex;
};
struct mapper { void *tex[4]; };
struct aimagereader_vk_stable {
    bool android_fel;
    int device, output_count;
    uint64_t fence_timeouts, submitted_outputs, completed_outputs, reclaimed_outputs;
    struct { void (*AImage_delete)(struct fake_image *); } api;
    struct mapper *mapper;
    struct vk_output outputs[1];
};
struct vo { struct aimagereader_vk_stable *gpu; };
struct priv {
    bool android_fel, software_el;
    void *queue;
    struct { struct vo *dr_vo; } stream_info;
    unsigned long long fel_staged, fel_stage_retries;
    int64_t fel_stage_ns, fel_stage_max_ns;
    double fel_stage_log_at;
};

static bool stage_fel_before_publish(struct priv *, struct mp_frame);
static bool finish_output(struct aimagereader_vk_stable *, struct vk_output *, uint64_t);
bool aimagereader_vk_stable_reuse(struct aimagereader_vk_stable *, struct mp_image *);
static int64_t now_ns, gpu_delay_ns;
static int prepare_calls, held_outputs, returned_outputs, injected_result;
static struct fake_image source_image;
static struct fake_fence fence;
static struct vk_input input;
static struct mapper mapper;
static struct aimagereader_vk_stable gpu;
static struct vo vo;
static struct priv producer;

static int64_t mp_time_ns(void) { return now_ns; }
static double mp_time_sec(void) { return now_ns / 1e9; }
static void mp_sleep_ns(int64_t duration) { assert(duration > 0); now_ns += duration; }
static bool vk_success(struct aimagereader_vk_stable *p, int result, const char *what)
{ (void)p; (void)what; return result == VK_SUCCESS; }
static VkResult vkGetFenceStatus(int device, VkFence f)
{ (void)device; return !f->reset && now_ns >= f->ready_at ? VK_SUCCESS : VK_NOT_READY; }
static VkResult vkWaitForFences(int device, int count, VkFence *f, int all, uint64_t timeout)
{
    assert(count == 1 && all && timeout);
    if (vkGetFenceStatus(device, *f) == VK_SUCCESS) return VK_SUCCESS;
    now_ns += timeout;
    return vkGetFenceStatus(device, *f) == VK_SUCCESS ? VK_SUCCESS : VK_TIMEOUT;
}
static VkResult vkResetFences(int device, int count, VkFence *f)
{ (void)device; assert(count == 1); (*f)->reset = true; return VK_SUCCESS; }
static void destroy_input(struct aimagereader_vk_stable *p, struct vk_input *i)
{ (void)p; assert(i->users == 0); }
static void delete_image(struct fake_image *img)
{
    assert(img == &source_image && !img->returned);
    assert(now_ns >= fence.ready_at); // Never return source before GPU completes.
    struct mp_image *src = gpu.outputs[0].source_frame;
    assert(!mp_android_fel_staging_ready(src->android_fel_staging->data));
    img->returned = true;
    held_outputs--;
    returned_outputs++;
}
static void clear_cached_frame(void)
{
    struct vk_output *out = &gpu.outputs[0];
    if (out->source_frame) {
        av_buffer_unref(&out->source_frame->android_fel_staging);
        free(out->source_frame);
        out->source_frame = NULL;
    }
}
static int vo_prepare_fel_frame(struct vo *v, struct mp_image *image)
{
    assert(v == &vo);
    prepare_calls++;
    if (injected_result != VO_TRUE) return injected_result;
    if (aimagereader_vk_stable_reuse(v->gpu, image)) return VO_TRUE;
    clear_cached_frame();
    image->android_fel_staging = av_buffer_allocz(1);
    assert(image->android_fel_staging);
    struct mp_image *ref = malloc(sizeof(*ref));
    assert(ref);
    *ref = *image;
    ref->android_fel_staging = av_buffer_ref(image->android_fel_staging);
    assert(ref->android_fel_staging);
    fence = (struct fake_fence){.ready_at = now_ns + gpu_delay_ns};
    input = (struct vk_input){.users = 1};
    gpu.outputs[0] = (struct vk_output){.fence = &fence,
        .source_image = &source_image, .source_frame = ref, .input = &input,
        .pending = true, .ratex = &gpu};
    gpu.submitted_outputs++;
    return VO_FALSE; // Submitted is not completed, even if the handle exists.
}
static void reset(void)
{
    clear_cached_frame();
    now_ns = 1000000000LL;
    prepare_calls = held_outputs = returned_outputs = 0;
    gpu_delay_ns = MP_TIME_MS_TO_NS(12);
    injected_result = VO_TRUE;
    gpu = (struct aimagereader_vk_stable){.android_fel = true, .output_count = 1,
        .api.AImage_delete = delete_image, .mapper = &mapper};
    vo = (struct vo){.gpu = &gpu};
    producer = (struct priv){.android_fel = true, .queue = &producer,
        .stream_info.dr_vo = &vo};
}
static struct mp_image make_frame(int id)
{
    assert(held_outputs == 0); // Calling the codec here would stall otherwise.
    held_outputs++;
    source_image = (struct fake_image){0};
    return (struct mp_image){.imgfmt = IMGFMT_MEDIACODEC, .pts = id / 24.0,
        .planes[3] = (void *)(uintptr_t)(id + 1)};
}
static void test_handoff(void)
{
    reset();
    // Old publish-then-decode order still owns this output at its next call.
    struct mp_image image = make_frame(0);
    assert(held_outputs == 1 && !image.android_fel_staging);
    // The actual new handoff closes that window without changing the codec.
    for (int id = 0; id < 120; id++) {
        if (id) image = make_frame(id);
        assert(stage_fel_before_publish(&producer, (struct mp_frame){MP_FRAME_VIDEO, &image}));
        assert(held_outputs == 0 && source_image.returned && input.users == 0);
        assert(mp_android_fel_staging_ready(image.android_fel_staging->data));
        assert(!gpu.outputs[0].pending && gpu.completed_outputs == (unsigned)id + 1);
        assert(image.pts == id / 24.0); // Pairing timestamp is unchanged.
        av_buffer_unref(&image.android_fel_staging);
    }
    assert(producer.fel_staged == 120 && returned_outputs == 120);
    assert(producer.fel_stage_max_ns == gpu_delay_ns);
    clear_cached_frame();
}
static void test_timeout_and_isolation(void)
{
    reset();
    struct mp_image image = make_frame(0);
    gpu_delay_ns = MP_TIME_MS_TO_NS(2000);
    int64_t started = now_ns;
    assert(!stage_fel_before_publish(&producer, (struct mp_frame){MP_FRAME_VIDEO, &image}));
    assert(now_ns - started == MP_TIME_MS_TO_NS(750));
    assert(held_outputs == 1 && !producer.fel_staged);
    assert(!mp_android_fel_staging_ready(image.android_fel_staging->data));
    now_ns = fence.ready_at; // Cleanup remains GPU-safe after a timed-out caller.
    assert(finish_output(&gpu, &gpu.outputs[0], 0));
    assert(held_outputs == 0 && returned_outputs == 1);
    av_buffer_unref(&image.android_fel_staging);
    clear_cached_frame();

    for (int kind = 0; kind < 7; kind++) {
        reset();
        image = (struct mp_image){.imgfmt = IMGFMT_MEDIACODEC};
        struct mp_frame frame = {MP_FRAME_VIDEO, &image};
        if (kind == 0) producer.android_fel = false;
        if (kind == 1) producer.software_el = true;
        if (kind == 2) producer.queue = NULL;
        if (kind == 3) frame.type = MP_FRAME_EOF;
        if (kind == 4) image.imgfmt = IMGFMT_YUV420P10;
        if (kind == 5) injected_result = VO_NOTIMPL;
        if (kind == 6) injected_result = VO_ERROR;
        assert(stage_fel_before_publish(&producer, frame) == (kind != 6));
        assert(!image.android_fel_staging && !producer.fel_staged);
        assert(prepare_calls == (kind >= 5));
    }
}
int main(void)
{
    assert(!mp_android_fel_staging_ready(NULL));
    mp_android_fel_staging_complete(NULL);
    test_handoff();
    test_timeout_and_isolation();
    puts("PASS: actual producer handoff and GPU completion/cache-hit bodies: 120 limited-pool frames, fence-before-return-before-publish, bounded timeout, opt-in isolation");
    return 0;
}
