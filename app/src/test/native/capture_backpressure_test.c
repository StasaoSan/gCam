/* Runs the production capture loop and real shared EGL contexts. Only vendor
 * QCarCam/ION and Android Window boundaries are replaced on the host. */
#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window_jni.h>
static void *test_dlopen(const char *,int);
static void *test_dlsym(void *,const char *);
static int test_dlclose(void *);
static int test_open(const char *,int,...);
static int test_close(int);
static int test_ioctl(int,unsigned long,...);
static void *test_mmap(void *,size_t,int,int,int,off_t);
static int test_munmap(void *,size_t);
static EGLSurface test_create_window(EGLDisplay,EGLConfig,EGLNativeWindowType,const EGLint *);
static EGLBoolean test_swap(EGLDisplay,EGLSurface);
static EGLBoolean test_choose(EGLDisplay,const EGLint *,EGLConfig *,EGLint,EGLint *);
static GLsync test_fence(GLenum,GLbitfield);
static void test_tex_sub_image(GLenum,GLint,GLint,GLint,GLsizei,GLsizei,GLenum,GLenum,const void *);
static void test_gen_textures(GLsizei,GLuint *);
static EGLContext test_create_context(EGLDisplay,EGLConfig,EGLContext,const EGLint *);
static EGLBoolean test_make_current(EGLDisplay,EGLSurface,EGLSurface,EGLContext);
static EGLBoolean test_destroy_surface(EGLDisplay,EGLSurface);
static int test_clock_gettime(clockid_t,struct timespec *);
static EGLint test_egl_get_error(void);
static void test_gl_wait_sync(GLsync,GLbitfield,GLuint64);
static void test_gl_finish(void);
#define dlopen test_dlopen
#define dlsym test_dlsym
#define dlclose test_dlclose
#define open test_open
#define close test_close
#define ioctl test_ioctl
#define mmap test_mmap
#define munmap test_munmap
#define eglCreateWindowSurface test_create_window
#define eglSwapBuffers test_swap
#define eglChooseConfig test_choose
#define glFenceSync test_fence
#define glTexSubImage2D test_tex_sub_image
#define glGenTextures test_gen_textures
#define eglCreateContext test_create_context
#define eglMakeCurrent test_make_current
#define eglDestroySurface test_destroy_surface
#define clock_gettime test_clock_gettime
#define eglGetError test_egl_get_error
#define glWaitSync test_gl_wait_sync
#define glFinish test_gl_finish
#include "../../main/cpp/qcarcam_jni.c"
#undef dlopen
#undef dlsym
#undef dlclose
#undef open
#undef close
#undef ioctl
#undef mmap
#undef munmap
#undef eglCreateWindowSurface
#undef eglSwapBuffers
#undef eglChooseConfig
#undef glFenceSync
#undef glTexSubImage2D
#undef glGenTextures
#undef eglCreateContext
#undef eglMakeCurrent
#undef eglDestroySurface
#undef clock_gettime
#undef eglGetError
#undef glWaitSync
#undef glFinish

static atomic_bool block_encoder,encoder_entered,release_encoder;
static atomic_bool block_hud,hud_entered,release_hud;
static atomic_bool fail_swap_once;
static atomic_bool fail_hud_swap_once;
static atomic_bool fail_copy_fence_once;
static atomic_bool fail_recorder_current_once;
static atomic_bool fail_recorder_detach_once;
static atomic_bool recorder_detach_failed;
static atomic_bool block_recorder_wait,recorder_wait_entered,release_recorder_wait;
static atomic_bool block_recorder_finish,recorder_finish_entered,release_recorder_finish;
static _Thread_local EGLint injected_egl_error;
static atomic_bool pause_source,source_paused;
static atomic_bool pause_upload,upload_entered,release_upload;
static atomic_bool pause_sync,sync_entered,release_sync,fail_sync_once;
static atomic_uint encoder_releases;
static pthread_mutex_t gate_mutex=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t gate_cond=PTHREAD_COND_INITIALIZER;
static pthread_mutex_t surfaces_mutex=PTHREAD_MUTEX_INITIALIZER;
static struct { EGLSurface surface; ANativeWindow *window; unsigned current_calls; int recorder, blocked_hud; } test_surfaces[64];
static unsigned test_surface_count;
static atomic_uint next_memory;
static atomic_uint physical_opens;
static atomic_uint source_gets,source_releases;
static atomic_uint texture_allocations,context_allocations;
static atomic_bool variable_pixels;
static atomic_bool synthetic_cpu_clock;
static _Thread_local uint64_t synthetic_cpu_ticks;
static atomic_uint last_source_luma,resumed_hud_luma;
static void *test_memory[64];
static void *source_memory[NBUF];
static bool source_acquired[NBUF];
static unsigned fake_frame;
static EGLint test_egl_get_error(void){
    EGLint error=injected_egl_error;
    if(error){injected_egl_error=0;return error;}
    return eglGetError();
}
static int recorder_owner_thread(void){
    async_recorder_t *worker=shared_cameras[0].recorder;
    return worker&&pthread_equal(pthread_self(),worker->thread);
}
static void test_gl_wait_sync(GLsync sync,GLbitfield flags,GLuint64 timeout){
    if(atomic_load(&block_recorder_wait)&&recorder_owner_thread()){
        pthread_mutex_lock(&gate_mutex);atomic_store(&recorder_wait_entered,true);
        while(!atomic_load(&release_recorder_wait))pthread_cond_wait(&gate_cond,&gate_mutex);
        pthread_mutex_unlock(&gate_mutex);
    }
    glWaitSync(sync,flags,timeout);
}
static void test_gl_finish(void){
    if(atomic_load(&block_recorder_finish)&&recorder_owner_thread()){
        pthread_mutex_lock(&gate_mutex);atomic_store(&recorder_finish_entered,true);
        while(!atomic_load(&release_recorder_finish))pthread_cond_wait(&gate_cond,&gate_mutex);
        pthread_mutex_unlock(&gate_mutex);
    }
    glFinish();
}
static int test_clock_gettime(clockid_t clock,struct timespec *value){
    if(clock==CLOCK_THREAD_CPUTIME_ID&&atomic_load(&synthetic_cpu_clock)){
        /* The CPU clock is the external measurement boundary. Wall time stays
         * real so an intentionally blocked swap cannot satisfy CPU counters. */
        uint64_t ns=(++synthetic_cpu_ticks)*10000;
        value->tv_sec=(time_t)(ns/1000000000ull);value->tv_nsec=(long)(ns%1000000000ull);return 0;
    }
    return clock_gettime(clock,value);
}
static void test_gen_textures(GLsizei count,GLuint *textures){
    atomic_fetch_add(&texture_allocations,(unsigned)count);glGenTextures(count,textures);
}
static EGLContext test_create_context(EGLDisplay display,EGLConfig config,EGLContext shared,const EGLint *attributes){
    atomic_fetch_add(&context_allocations,1);return eglCreateContext(display,config,shared,attributes);
}
static EGLBoolean test_make_current(EGLDisplay display,EGLSurface draw,EGLSurface read,EGLContext context){
    int recorder=0,detaching_recorder=0;
    pthread_mutex_lock(&surfaces_mutex);
    for(unsigned i=test_surface_count;i>0;i--)if(test_surfaces[i-1].surface==draw){
        test_surfaces[i-1].current_calls++;recorder=test_surfaces[i-1].recorder;break;
    }
    EGLSurface current=eglGetCurrentSurface(EGL_DRAW);
    if(draw!=current)for(unsigned i=test_surface_count;i>0;i--)
        if(test_surfaces[i-1].surface==current){detaching_recorder=test_surfaces[i-1].recorder;break;}
    pthread_mutex_unlock(&surfaces_mutex);
    if(recorder&&atomic_exchange(&fail_recorder_current_once,false)){
        injected_egl_error=EGL_BAD_SURFACE;return EGL_FALSE;
    }
    if(detaching_recorder&&atomic_exchange(&fail_recorder_detach_once,false)){
        atomic_store(&recorder_detach_failed,true);injected_egl_error=EGL_BAD_ACCESS;return EGL_FALSE;
    }
    return eglMakeCurrent(display,draw,read,context);
}
static EGLBoolean test_destroy_surface(EGLDisplay display,EGLSurface surface){
    assert(eglGetCurrentSurface(EGL_DRAW)!=surface&&eglGetCurrentSurface(EGL_READ)!=surface&&
           "an output window must be detached before destruction");
    return eglDestroySurface(display,surface);
}
static unsigned window_current_calls(ANativeWindow *window){
    unsigned calls=0;
    pthread_mutex_lock(&surfaces_mutex);
    for(unsigned i=0;i<test_surface_count;i++)if(test_surfaces[i].window==window)
        calls+=test_surfaces[i].current_calls;
    pthread_mutex_unlock(&surfaces_mutex);return calls;
}
static GLsync test_fence(GLenum condition,GLbitfield flags){
    GLint fbo=0;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&fbo);
    if(fbo&&atomic_load(&fail_copy_fence_once)){
        GLint attachment=0;glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME,&attachment);
        async_recorder_t *worker=shared_cameras[0].recorder;
        for(unsigned i=0;worker&&i<NBUF;i++)if(worker->slots[i].texture==(GLuint)attachment&&atomic_exchange(&fail_copy_fence_once,false)){
            glBindTexture(0xdeadcafeu,0);return NULL;
        }
    }
    return glFenceSync(condition,flags);
}
static void test_tex_sub_image(GLenum target,GLint level,GLint x,GLint y,
        GLsizei width,GLsizei height,GLenum format,GLenum type,const void *pixels){
    if(width==W/2&&height==H&&atomic_load(&pause_upload)&&
            pthread_equal(pthread_self(),shared_cameras[0].thread)){
        pthread_mutex_lock(&gate_mutex);atomic_store(&upload_entered,true);
        while(!atomic_load(&release_upload))pthread_cond_wait(&gate_cond,&gate_mutex);
        pthread_mutex_unlock(&gate_mutex);
    }
    glTexSubImage2D(target,level,x,y,width,height,format,type,pixels);
}
int __android_log_print(int priority,const char *tag,const char *format,...){
    (void)priority;(void)tag;
    if(getenv("GCAM_TEST_VERBOSE")){va_list ap;va_start(ap,format);vfprintf(stderr,format,ap);va_end(ap);fputc('\n',stderr);}
    return 0;
}
int __system_property_get(const char *key,char *value){
    if(!strcmp(key,"debug.gcam.upload")){
        const char *mode=getenv("GCAM_TEST_UPLOAD");if(mode){strcpy(value,mode);return (int)strlen(mode);}
    }
    if(!strcmp(key,"debug.gcam.ion_cached")){
        const char *cached=getenv("GCAM_TEST_CACHED");strcpy(value,cached?cached:"0");return 1;
    }
    value[0]=0;return 0;
}
ANativeWindow *ANativeWindow_fromSurface(JNIEnv *env,jobject surface){(void)env;ANativeWindow *w=(void *)surface;w->refs++;return w;}
void ANativeWindow_release(ANativeWindow *window){assert(atomic_fetch_sub(&window->refs,1)>0);}
void ANativeWindow_acquire(ANativeWindow *window){window->refs++;}
int ANativeWindow_setBuffersGeometry(ANativeWindow *w,int width,int height,int fmt){(void)width;(void)height;(void)fmt;atomic_fetch_add(&w->geometry_calls,1);return 0;}
static EGLBoolean test_choose(EGLDisplay d,const EGLint *attrs,EGLConfig *cfg,EGLint size,EGLint *count){
    EGLint host[64];int n=0;
    for(int i=0;attrs[i]!=EGL_NONE;i+=2){
        if(attrs[i]==EGL_RECORDABLE_ANDROID)continue;
        host[n++]=attrs[i];host[n++]=attrs[i+1];
    }
    host[n]=EGL_NONE;return eglChooseConfig(d,host,cfg,size,count);
}
static EGLSurface test_create_window(EGLDisplay d,EGLConfig cfg,EGLNativeWindowType native,const EGLint *attrs){
    (void)attrs;ANativeWindow *w=(void *)native;
    EGLint pb[]={EGL_WIDTH,w->width,EGL_HEIGHT,w->height,EGL_NONE};
    EGLSurface result=eglCreatePbufferSurface(d,cfg,pb);assert(result!=EGL_NO_SURFACE);
    pthread_mutex_lock(&surfaces_mutex);assert(test_surface_count<64);
    test_surfaces[test_surface_count++]=(typeof(test_surfaces[0])){
        .surface=result,.window=w,.recorder=w->recorder,.blocked_hud=w->width==320};
    pthread_mutex_unlock(&surfaces_mutex);return result;
}
static EGLBoolean test_swap(EGLDisplay d,EGLSurface surface){
    int recorder=0,blocked_hud=0;
    pthread_mutex_lock(&surfaces_mutex);
    for(unsigned i=0;i<test_surface_count;i++)if(test_surfaces[i].surface==surface){recorder=test_surfaces[i].recorder;blocked_hud=test_surfaces[i].blocked_hud;}
    pthread_mutex_unlock(&surfaces_mutex);
    GLint texture=0;unsigned char owned_pixel[4]={0};
    int check_owned=blocked_hud&&atomic_load(&variable_pixels);
    if(check_owned){
        /* Read the packed source texture actually bound by production drawing.
         * A stable window framebuffer alone would not detect source reuse. */
        glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture);
        GLint previous;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&previous);
        GLuint framebuffer;glGenFramebuffers(1,&framebuffer);glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,(GLuint)texture,0);
        assert(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE);
        glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,owned_pixel);
        glBindFramebuffer(GL_FRAMEBUFFER,(GLuint)previous);glDeleteFramebuffers(1,&framebuffer);
        assert(owned_pixel[0]==128&&owned_pixel[2]==128&&owned_pixel[1]==owned_pixel[3]);
    }
    int waited_for_hud=0;
    if(!recorder&&blocked_hud&&atomic_load(&block_hud)){
        pthread_mutex_lock(&gate_mutex);atomic_store(&hud_entered,true);
        waited_for_hud=!atomic_load(&release_hud);
        while(!atomic_load(&release_hud))pthread_cond_wait(&gate_cond,&gate_mutex);
        pthread_mutex_unlock(&gate_mutex);
    }
    if(check_owned&&waited_for_hud){
        unsigned char after[4]={0};GLint previous;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&previous);
        GLuint framebuffer;glGenFramebuffers(1,&framebuffer);glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,(GLuint)texture,0);
        glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,after);
        glBindFramebuffer(GL_FRAMEBUFFER,(GLuint)previous);glDeleteFramebuffers(1,&framebuffer);
        assert(!memcmp(owned_pixel,after,sizeof(after))&&"an in-flight HUD texture must survive capture reuse and queued replacement");
    }else if(check_owned&&atomic_load(&release_hud)){
        unsigned expected=0;atomic_compare_exchange_strong(&resumed_hud_luma,&expected,(unsigned)owned_pixel[1]);
    }
    if(recorder&&!atomic_load(&variable_pixels)){
        unsigned char pixel[4]={0};glReadPixels(100,100,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
        assert(pixel[0]>=128&&pixel[0]<=132&&pixel[1]>=128&&pixel[1]<=132&&pixel[2]>=128&&pixel[2]<=132&&pixel[3]==255);
    }
    if(recorder&&atomic_load(&block_encoder)){
        pthread_mutex_lock(&gate_mutex);atomic_store(&encoder_entered,true);
        while(!atomic_load(&release_encoder))pthread_cond_wait(&gate_cond,&gate_mutex);
        pthread_mutex_unlock(&gate_mutex);atomic_fetch_add(&encoder_releases,1);
    }
    if(recorder&&atomic_exchange(&fail_swap_once,false))return EGL_FALSE;
    if(!recorder&&blocked_hud&&atomic_exchange(&fail_hud_swap_once,false))return EGL_FALSE;
    return eglSwapBuffers(d,surface);
}
static int test_open(const char *path,int flags,...){(void)flags;assert(!strcmp(path,"/dev/ion"));return 2000;}
static int test_close(int fd){assert(fd==2000||(fd>=1000&&fd<1064));return 0;}
static int test_ioctl(int fd,unsigned long op,...){
    va_list ap;va_start(ap,op);void *arg=va_arg(ap,void *);va_end(ap);
    if(fd==2000){assert(op==ION_IOC_ALLOC_G636);unsigned index=atomic_fetch_add(&next_memory,1);assert(index<64);((ion_alloc_t *)arg)->fd=1000+index;return 0;}
    assert(op==DMA_BUF_IOCTL_SYNC);
    struct dma_buf_sync *sync=arg;
    if(sync->flags==(DMA_BUF_SYNC_START|DMA_BUF_SYNC_READ)){
        if(atomic_load(&pause_sync)){
            pthread_mutex_lock(&gate_mutex);atomic_store(&sync_entered,true);
            while(!atomic_load(&release_sync))pthread_cond_wait(&gate_cond,&gate_mutex);
            pthread_mutex_unlock(&gate_mutex);
        }
        if(atomic_exchange(&fail_sync_once,false)){errno=EIO;return -1;}
    }
    return 0;
}
static void *test_mmap(void *addr,size_t size,int prot,int flags,int fd,off_t offset){
    (void)addr;(void)prot;(void)flags;(void)offset;assert(fd>=1000&&fd<1064);
    void *p=malloc(size);assert(p);memset(p,128,size);test_memory[fd-1000]=p;return p;
}
static int test_munmap(void *p,size_t n){(void)n;free(p);return 0;}
static int fake_init(const void *arg){(void)arg;return 0;}
static int fake_uninit(void){return 0;}
static int fake_query(void *p,unsigned n,unsigned *out){(void)p;(void)n;(void)out;return 0;}
static qhandle_t fake_open(unsigned input){assert(input==0);atomic_fetch_add(&physical_opens,1);return (void *)1;}
static int fake_handle(qhandle_t cam){assert(cam==(void *)1);return 0;}
static int fake_buffers(qhandle_t cam,qbuffers_t *buffers){
    (void)cam;assert(buffers->n_buffers==3);
    for(unsigned i=0;i<NBUF;i++){
        assert(!source_acquired[i]);
        source_memory[i]=test_memory[buffers->buffers[i].planes[0].mem_handle-1000];
    }
    return 0;
}
static int fake_param(qhandle_t cam,int id,void *p){(void)cam;(void)id;(void)p;return 0;}
static int fake_get(qhandle_t cam,qframe_t *frame,uint64_t timeout,uint32_t flags){
    (void)cam;(void)timeout;(void)flags;usleep(40000);
    if(atomic_load(&pause_source)){
        pthread_mutex_lock(&gate_mutex);atomic_store(&source_paused,true);
        while(atomic_load(&pause_source))pthread_cond_wait(&gate_cond,&gate_mutex);
        atomic_store(&source_paused,false);pthread_mutex_unlock(&gate_mutex);
    }
    frame->idx=fake_frame++%3;
    assert(!source_acquired[frame->idx]&&"capture cannot acquire an unreleased vendor buffer");source_acquired[frame->idx]=true;
    if(atomic_load(&variable_pixels)){
        unsigned char luma=(unsigned char)(32+fake_frame*2);unsigned char *pixels=source_memory[frame->idx];
        for(unsigned i=0;i<BYTES;i+=4){pixels[i]=128;pixels[i+1]=luma;pixels[i+2]=128;pixels[i+3]=luma;}
        atomic_store(&last_source_luma,luma);
    }
    atomic_fetch_add(&source_gets,1);return 0;
}
static int fake_release(qhandle_t cam,unsigned index){
    (void)cam;assert(index<3);assert(source_acquired[index]&&"vendor source buffers must be released exactly once");source_acquired[index]=false;
    if(atomic_load(&variable_pixels))memset(source_memory[index],0x3d,BYTES);
    atomic_fetch_add(&source_releases,1);return 0;
}
static void *test_dlopen(const char *name,int flags){(void)flags;assert(strstr(name,"libais_hidl_client.so"));return (void *)7;}
static int test_dlclose(void *handle){assert(handle==(void *)7);return 0;}
static void *test_dlsym(void *handle,const char *name){
    assert(handle==(void *)7);
    if(!strcmp(name,"qcarcam_initialize"))return fake_init;
    if(!strcmp(name,"qcarcam_uninitialize"))return fake_uninit;
    if(!strcmp(name,"qcarcam_query_inputs"))return fake_query;
    if(!strcmp(name,"qcarcam_open"))return fake_open;
    if(!strcmp(name,"qcarcam_close")||!strcmp(name,"qcarcam_start")||!strcmp(name,"qcarcam_stop"))return fake_handle;
    if(!strcmp(name,"qcarcam_s_buffers"))return fake_buffers;
    if(!strcmp(name,"qcarcam_s_param"))return fake_param;
    if(!strcmp(name,"qcarcam_get_frame"))return fake_get;
    if(!strcmp(name,"qcarcam_release_frame"))return fake_release;
    abort();
}
static void wait_flag(atomic_bool *flag){
    for(int i=0;i<500&&!atomic_load(flag);i++)usleep(10000);
    assert(atomic_load(flag));
}
static void wait_frames(state_t *state,unsigned count){
    for(int i=0;i<500&&atomic_load(&state->frames)<count;i++)usleep(10000);
    assert(atomic_load(&state->frames)>=count);
}
static void wait_source_gets(unsigned count){
    for(int i=0;i<500&&atomic_load(&source_gets)<count;i++)usleep(10000);
    assert(atomic_load(&source_gets)>=count);
}
static void unpause_source(void){
    pthread_mutex_lock(&gate_mutex);atomic_store(&pause_source,false);
    pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
}
static jstring fake_string(JNIEnv *env,const char *value){(void)env;return (jstring)value;}
static atomic_bool recorder_stop_threw;
static char test_throw_message[512];
static int exception_restart_output=-1;
static ANativeWindow *exception_restart_window;
static jclass fake_find_class(JNIEnv *env,const char *name){
    assert(!strcmp(name,"java/lang/IllegalStateException"));
    if(exception_restart_output>=0){
        /* Class resolution is outside the native lifecycle lock. Model a
         * concurrent Binder client finishing the old output and starting a
         * real new owner before this caller materializes its exception. */
        int output=exception_restart_output;exception_restart_output=-1;
        atomic_store(&block_encoder,false);atomic_store(&block_hud,false);
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_encoder,true);
        atomic_store(&release_hud,true);pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        assert(!shared_stop(0,output));
        assert(strstr((const char *)shared_start(env,(jobject)exception_restart_window,0,output,25),"Запуск принят"));
        wait_frames(shared_cameras[0].outputs[output].stats,3);
    }
    return (jclass)1;
}
static jint fake_throw(JNIEnv *env,jclass type,const char *message){
    (void)env;assert(type==(jclass)1);assert(strstr(message,"EGL target still owned"));
    snprintf(test_throw_message,sizeof(test_throw_message),"%s",message);
    atomic_store(&recorder_stop_threw,true);return 0;
}
static jlong test_stats_values[80];
static jsize test_stats_length;
static jlongArray fake_long_array(JNIEnv *env,jsize length){
    (void)env;assert(length<=80);test_stats_length=length;return (jlongArray)test_stats_values;
}
static void fake_long_region(JNIEnv *env,jlongArray array,jsize start,jsize count,const jlong *values){
    (void)env;assert(array==(jlongArray)test_stats_values&&start==0&&count==test_stats_length);
    memcpy(test_stats_values,values,(size_t)count*sizeof(jlong));
}
int main(int argc,char **argv){
    pthread_once(&shared_once,init_shared);
    shared_camera_t *camera=&shared_cameras[0];
    ANativeWindow hud={.width=320,.height=320,.refs=1},encoder={.width=640,.height=400,.recorder=1,.refs=1};
    camera->outputs[2].pending=&hud;camera->outputs[2].wanted=1;
    camera->outputs[HUD_STREAMS].pending=&encoder;camera->outputs[HUD_STREAMS].wanted=1;
    reset_output_stats(&hud_streams[2],0,0,20);reset_output_stats(&recorders[0],0,1,25);
    int test_hud_isolation=argc>1&&!strcmp(argv[1],"--hud-isolation");
    int test_fence_failure=argc>1&&!strcmp(argv[1],"--fence-failure");
    int test_hud_failure=argc>1&&!strcmp(argv[1],"--hud-swap-failure");
    int test_hud_geometry=argc>1&&!strcmp(argv[1],"--hud-geometry");
    int test_recorder_stop_ack=argc>1&&!strcmp(argv[1],"--recorder-stop-ack");
    int test_admission_all_busy=argc>1&&!strcmp(argv[1],"--admission-all-busy");
    int test_admission_peer=argc>1&&!strcmp(argv[1],"--admission-peer");
    int test_admission_generation=argc>1&&!strcmp(argv[1],"--admission-generation");
    int test_admission_stop=argc>1&&!strcmp(argv[1],"--admission-stop");
    int test_admission_sync_failure=argc>1&&!strcmp(argv[1],"--admission-sync-failure");
    int test_surface_reuse=argc>1&&!strcmp(argv[1],"--surface-reuse");
    int test_surface_failure=argc>1&&!strcmp(argv[1],"--surface-current-failure");
    int test_surface_detach_failure=argc>1&&!strcmp(argv[1],"--surface-detach-failure");
    int test_frame_cpu=argc>1&&!strcmp(argv[1],"--frame-cpu-metrics");
    int test_pending_swap=argc>1&&!strcmp(argv[1],"--pending-phase-swap");
    int test_pending_wait=argc>1&&!strcmp(argv[1],"--pending-phase-wait");
    int test_pending_finish=argc>1&&!strcmp(argv[1],"--pending-phase-finish");
    int test_exception_recorder=argc>1&&!strcmp(argv[1],"--exception-new-recorder-owner");
    int test_exception_hud=argc>1&&!strcmp(argv[1],"--exception-new-hud-owner");
    if(test_admission_all_busy||test_admission_generation||test_admission_stop){
        camera->outputs[2].pending=NULL;camera->outputs[2].wanted=0;hud.refs=0;
    }
    if(test_admission_generation)setenv("GCAM_TEST_UPLOAD","direct",1);
    if(test_admission_stop||test_admission_sync_failure)setenv("GCAM_TEST_CACHED","1",1);
    ANativeWindow other_hud={.width=400,.height=320,.refs=1};
    if(test_hud_isolation||test_fence_failure||test_hud_failure){
        camera->outputs[6].pending=&other_hud;camera->outputs[6].wanted=1;
        reset_output_stats(&hud_streams[6],0,0,25);
    }
    atomic_store(&variable_pixels,test_hud_isolation);
    atomic_store(&fail_hud_swap_once,test_hud_failure);
    atomic_store(&block_encoder,!test_hud_isolation&&!test_hud_failure&&!test_hud_geometry&&
            !test_surface_reuse&&!test_surface_failure&&!test_surface_detach_failure&&!test_pending_wait&&!test_pending_finish&&!test_exception_hud);
    atomic_store(&block_hud,test_hud_isolation||test_exception_hud);
    atomic_store(&fail_recorder_current_once,test_surface_failure);
    atomic_store(&synthetic_cpu_clock,test_frame_cpu);
    atomic_store(&block_recorder_wait,test_pending_wait);
    if(test_pending_swap||test_pending_wait||test_pending_finish)camera->outputs[HUD_STREAMS].generation=7;
    if(test_exception_recorder||test_exception_hud)camera->outputs[test_exception_hud?2:HUD_STREAMS].generation=7;
    assert(!pthread_create(&camera->thread,NULL,shared_stream_thread,camera));camera->created=1;
    struct JNINativeInterface_ native={.NewStringUTF=fake_string,.FindClass=fake_find_class,.ThrowNew=fake_throw,
        .NewLongArray=fake_long_array,.SetLongArrayRegion=fake_long_region};JNIEnv env=&native;
    if(test_exception_recorder||test_exception_hud){
        int output=test_exception_hud?2:HUD_STREAMS;
        if(test_exception_hud){wait_flag(&hud_entered);wait_frames(&recorders[0],4);}
        else {wait_flag(&encoder_entered);wait_frames(&hud_streams[2],4);}
        ANativeWindow next={.width=400,.height=400,.recorder=!test_exception_hud};
        exception_restart_output=output;exception_restart_window=&next;
        if(test_exception_hud)Java_com_stasao_gcam_NativeQCarCam_hudStop(&env,NULL,2);
        else Java_com_stasao_gcam_NativeQCarCam_recorderStop(&env,NULL,0);
        assert(atomic_load(&recorder_stop_threw)&&strstr(test_throw_message,"phase=swap(3)")&&
               strstr(test_throw_message,"phaseGeneration=7")&&
               strtoull(strstr(test_throw_message,"phaseAgeMs=")+11,NULL,10)>=1000&&
               "a concurrent real new owner must not replace the timed-out caller's exception snapshot");
        assert(next.refs>0&&"the replacement must actually render before the old exception is materialized");
        assert(!shared_stop(0,HUD_STREAMS)&&!shared_stop(0,2));
        assert(!encoder.refs&&!hud.refs&&!next.refs&&atomic_load(&physical_opens)==1&&
               atomic_load(&source_gets)==atomic_load(&source_releases));
        puts("PASS: JNI caller retains its own timeout snapshot across real concurrent output replacement");return 0;
    }
    if(test_pending_swap||test_pending_wait||test_pending_finish){
        if(test_pending_swap)wait_flag(&encoder_entered);
        if(test_pending_wait)wait_flag(&recorder_wait_entered);
        if(test_pending_finish){
            wait_frames(&recorders[0],4);atomic_store(&block_recorder_finish,true);
        }
        wait_frames(&hud_streams[2],4);
        uint64_t began=now_ns();assert(shared_stop(0,HUD_STREAMS)==-1);
        assert(now_ns()-began<1500000000ull&&encoder.refs>0);
        if(test_pending_finish)wait_flag(&recorder_finish_entered);
        state_stats(&env,&recorders[0]);
        assert(test_stats_length>=60&&"native stats must expose the current pending phase before a blocked call returns");
        int expected=test_pending_swap?3:test_pending_wait?1:4;
        assert(test_stats_values[56]==expected&&"pending output phase must identify swap, GPU wait or cleanup finish");
        assert(test_stats_values[57]>0&&(uint64_t)test_stats_values[57]<=now_ns());
        assert(test_stats_values[58]==EGL_SUCCESS&&"a pending operation alone is not an EGL failure");
        assert(test_stats_values[59]==7&&"a timed-out cancellation must retain the actual output owner's generation");
        ANativeWindow pending_replacement={.width=640,.height=400,.recorder=1};
        assert(strstr((const char *)shared_start(&env,(jobject)&pending_replacement,0,HUD_STREAMS,25),"Запуск принят"));
        state_stats(&env,&recorders[0]);
        assert(test_stats_values[56]==expected&&test_stats_values[57]>0&&test_stats_values[59]==7&&
               "a new requested window must not erase the still-blocked previous owner's phase");
        Java_com_stasao_gcam_NativeQCarCam_recorderStop(&env,NULL,0);
        assert(atomic_load(&recorder_stop_threw)&&strstr(test_throw_message,"phase=")&&
               strstr(test_throw_message,"phaseAgeMs=")&&strstr(test_throw_message,"phaseGeneration=7")&&
               strstr(test_throw_message,"eglError=0x3000")&&"the timeout exception must retain diagnostics in the persistent recorder journal");
        assert(strtoull(strstr(test_throw_message,"phaseAgeMs=")+11,NULL,10)>=1000&&
               "the persistent exception must snapshot time spent inside the pending call");
        char remembered[512];snprintf(remembered,sizeof(remembered),"%s",test_throw_message);
        unsigned displayed=(unsigned)atomic_load(&hud_streams[2].frames);wait_frames(&hud_streams[2],displayed+3);
        atomic_store(&block_encoder,false);atomic_store(&block_recorder_wait,false);atomic_store(&block_recorder_finish,false);
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_encoder,true);
        atomic_store(&release_recorder_wait,true);atomic_store(&release_recorder_finish,true);
        pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        assert(!shared_stop(0,HUD_STREAMS));
        state_stats(&env,&recorders[0]);
        assert(test_stats_values[56]==0&&test_stats_values[57]==0&&"finished cleanup must clear its pending phase");
        assert(!strstr(remembered,"phase=idle")&&strstr(remembered,"phaseGeneration=7")&&
               strtoull(strstr(remembered,"phaseAgeMs=")+11,NULL,10)>=1000&&
               "late exception serialization must keep the timeout snapshot after the driver unblocks");
        assert(!shared_stop(0,2)&&!encoder.refs&&!pending_replacement.refs&&!hud.refs);
        assert(atomic_load(&source_gets)==atomic_load(&source_releases));
        puts("PASS: pending native phase survives a bounded detach timeout without blocking the peer output");return 0;
    }
    if(test_frame_cpu){
        wait_flag(&encoder_entered);wait_frames(&hud_streams[2],4);
        /* Keep the encoder's driver wait live while the source and HUD run. */
        usleep(120000);
        atomic_store(&block_encoder,false);
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_encoder,true);pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        wait_frames(&recorders[0],5);
        assert(!shared_stop(0,HUD_STREAMS));assert(!shared_stop(0,2));
        state_stats(&env,&recorders[0]);
        assert(test_stats_length>=56&&"native stats must expose output CPU and private-copy totals after existing counters");
        assert(test_stats_values[52]>=5&&test_stats_values[51]==10000*test_stats_values[52]&&
               "output timing must use its CPU clock, not blocked swap wall time");
        assert(test_stats_values[55]>=test_stats_values[52]&&test_stats_values[53]>0&&
               test_stats_values[54]==10000*test_stats_values[55]&&
               "each actual private copy must report wall and independent thread CPU time");
        state_stats(&env,&hud_streams[2]);
        assert(test_stats_values[52]>=4&&test_stats_values[51]==10000*test_stats_values[52]&&
               test_stats_values[55]>=test_stats_values[52]&&test_stats_values[54]==10000*test_stats_values[55]);
        assert(!encoder.refs&&!hud.refs&&atomic_load(&source_gets)==atomic_load(&source_releases));
        puts("PASS: native output and private-copy CPU metrics preserve the stats ABI and exclude blocked driver wall time");return 0;
    }
    if(test_surface_reuse){
        /* Regression: bouncing through the pbuffer and rebinding the window
         * on each frame changes no pixels but forces driver work every time. */
        wait_frames(&recorders[0],4);wait_frames(&hud_streams[2],4);
        unsigned recorder_binds=window_current_calls(&encoder),hud_binds=window_current_calls(&hud);
        unsigned encoded=(unsigned)atomic_load(&recorders[0].frames),displayed=(unsigned)atomic_load(&hud_streams[2].frames);
        wait_frames(&recorders[0],encoded+5);wait_frames(&hud_streams[2],displayed+5);
        assert(window_current_calls(&encoder)==recorder_binds&&
               "steady encoder frames must reuse their current EGL surface");
        assert(window_current_calls(&hud)==hud_binds&&
               "steady HUD frames must reuse their current EGL surface");
        ANativeWindow replacement={.width=640,.height=400,.recorder=1};
        assert(strstr((const char *)shared_start(&env,(jobject)&replacement,0,HUD_STREAMS,25),"Запуск принят"));
        wait_frames(&recorders[0],3);assert(!encoder.refs);
        unsigned replacement_binds=window_current_calls(&replacement);
        encoded=(unsigned)atomic_load(&recorders[0].frames);wait_frames(&recorders[0],encoded+5);
        assert(replacement_binds==1&&window_current_calls(&replacement)==1&&
               "a replacement output must bind once and then render with no old window");
        assert(!shared_stop(0,HUD_STREAMS));assert(!shared_stop(0,2));
        assert(!encoder.refs&&!replacement.refs&&!hud.refs);
        assert(atomic_load(&source_gets)==atomic_load(&source_releases)&&atomic_load(&physical_opens)==1);
        puts("PASS: steady and replaced outputs reuse EGL surfaces while preserving pixels and source ownership");return 0;
    }
    if(test_surface_failure){
        for(unsigned i=0;i<500&&encoder.refs;i++)usleep(10000);
        assert(!encoder.refs&&"failed output bind must detach and release its window");
        wait_frames(&hud_streams[2],5);
        assert(!atomic_load(&recorders[0].frames)&&"a failed bind must not publish a frame from the previous surface");
        assert(!shared_stop(0,HUD_STREAMS));assert(!shared_stop(0,2));
        assert(!hud.refs&&atomic_load(&source_gets)==atomic_load(&source_releases));
        state_stats(&env,&recorders[0]);
        assert(test_stats_length>=60&&test_stats_values[58]==EGL_BAD_SURFACE&&
               "a failed bind's EGL error survives later successful cleanup calls");
        puts("PASS: failed EGL window bind publishes no frame and preserves the peer camera");return 0;
    }
    if(test_surface_detach_failure){
        wait_frames(&recorders[0],4);wait_frames(&hud_streams[2],4);
        atomic_store(&fail_recorder_detach_once,true);
        assert(!shared_stop(0,HUD_STREAMS));
        assert(atomic_load(&recorder_detach_failed)&&!encoder.refs&&
               "failed pbuffer binding must be retried before the old window can be destroyed");
        unsigned displayed=(unsigned)atomic_load(&hud_streams[2].frames);
        wait_frames(&hud_streams[2],displayed+3);
        assert(!shared_stop(0,2)&&!hud.refs&&atomic_load(&source_gets)==atomic_load(&source_releases));
        state_stats(&env,&recorders[0]);
        assert(test_stats_length>=60&&test_stats_values[58]==EGL_BAD_ACCESS&&
               "a retried detach must preserve the last actual EGL failure");
        puts("PASS: a failed EGL detach never poisons the binding cache or destroys a current window");return 0;
    }
    if(test_admission_all_busy){
        /* If all fixed consumer slots are busy, a captured source frame has no
         * destination. Regression: do not sync ION, map/copy PBO or upload it. */
        wait_flag(&encoder_entered);
        atomic_store(&pause_source,true);wait_flag(&source_paused);
        async_recorder_t *worker=camera->recorder;assert(worker);
        for(unsigned i=0;i<NBUF;i++)atomic_store(&worker->slots[i].state,REC_READING);
        unsigned gets=atomic_load(&source_gets),tex=atomic_load(&recorders[0].tex_count);
        unsigned maps=atomic_load(&recorders[0].map_count),sync=atomic_load(&recorders[0].sync_count);
        unsigned textures=atomic_load(&texture_allocations),replaced=atomic_load(&recorders[0].replaced);
        unpause_source();wait_source_gets(gets+5);
        atomic_store(&pause_source,true);wait_flag(&source_paused);
        assert(atomic_load(&source_gets)==atomic_load(&source_releases)&&"every rejected frame must return its QCarCam source buffer");
        assert(atomic_load(&recorders[0].tex_count)==tex&&"no GPU upload when all destinations are full");
        assert(atomic_load(&recorders[0].map_count)==maps&&"no PBO map or CPU copy when all destinations are full");
        assert(atomic_load(&recorders[0].sync_count)==sync&&"no ION cache sync when all destinations are full");
        assert(atomic_load(&recorders[0].replaced)>replaced&&"backpressure must still count rejected frames");
        assert(atomic_load(&recorders[0].source_frames_no_capacity)>0&&
               atomic_load(&recorders[0].source_uploads)==tex&&"admission counters must distinguish skipped frames from actual uploads");
        assert(atomic_load(&texture_allocations)==textures&&atomic_load(&physical_opens)==1&&"admission must preserve bounded storage and one physical source");
        unpause_source();atomic_store(&block_encoder,false);
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_encoder,true);pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        assert(!shared_stop(0,HUD_STREAMS));
        assert(!encoder.refs&&atomic_load(&source_gets)==atomic_load(&source_releases));
        puts("PASS: full destination pool skips source sync/copy/upload and releases every QCarCam frame");return 0;
    }
    if(test_admission_peer){
        wait_flag(&encoder_entered);wait_frames(&hud_streams[2],3);
        atomic_store(&pause_source,true);wait_flag(&source_paused);
        async_recorder_t *worker=camera->recorder;assert(worker);
        for(unsigned i=0;i<NBUF;i++)atomic_store(&worker->slots[i].state,REC_READING);
        unsigned gets=atomic_load(&source_gets),uploads=atomic_load(&recorders[0].source_uploads);
        unsigned offers=atomic_load(&recorders[0].accepted_offers),tex=atomic_load(&recorders[0].tex_count);
        unsigned long long hud_frames=atomic_load(&hud_streams[2].frames);
        unpause_source();wait_frames(&hud_streams[2],(unsigned)hud_frames+5);
        atomic_store(&pause_source,true);wait_flag(&source_paused);
        unsigned uploaded=atomic_load(&recorders[0].source_uploads)-uploads;
        assert(atomic_load(&source_gets)>=gets+5&&uploaded>=5&&
               "the available HUD must keep receiving frames while the encoder is full");
        assert(atomic_load(&recorders[0].accepted_offers)-offers==uploaded&&
               "each source upload may be offered only to its reserved HUD destination");
        assert(atomic_load(&recorders[0].tex_count)-tex==uploaded&&
               "one physical source frame must incur only one source upload");
        assert(atomic_load(&source_gets)==atomic_load(&source_releases)&&atomic_load(&physical_opens)==1);
        unpause_source();atomic_store(&block_encoder,false);
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_encoder,true);pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        assert(!shared_stop(0,HUD_STREAMS));assert(!shared_stop(0,2));
        assert(!encoder.refs&&!hud.refs&&atomic_load(&source_gets)==atomic_load(&source_releases));
        puts("PASS: one available HUD receives one offer and one upload despite a full encoder");return 0;
    }
    if(test_admission_generation){
        wait_flag(&encoder_entered);
        atomic_store(&pause_upload,true);wait_flag(&upload_entered);
        async_recorder_t *worker=camera->recorder;assert(worker);
        recorder_slot_t *reserved=NULL;
        for(unsigned i=0;i<NBUF;i++)if(atomic_load(&worker->slots[i].state)==REC_WRITING)
            reserved=&worker->slots[i];
        assert(reserved&&"producer must own a concrete destination before source upload");
        atomic_store(&pause_source,true);
        unsigned offers=atomic_load(&recorders[0].accepted_offers);
        unsigned cancelled=atomic_load(&recorders[0].cancelled_reservations);
        ANativeWindow next_encoder={.width=640,.height=400,.recorder=1};
        assert(strstr((const char *)shared_start(&env,(jobject)&next_encoder,0,HUD_STREAMS,25),"Запуск принят"));
        atomic_store(&pause_upload,false);
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_upload,true);
        pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        wait_flag(&source_paused);
        assert(atomic_load(&recorders[0].accepted_offers)==offers&&
               "a frame reserved for an obsolete generation must not be published");
        assert(atomic_load(&recorders[0].cancelled_reservations)>cancelled&&
               atomic_load(&reserved->state)==REC_FREE&&"cancelling a reserved frame must return its destination slot");
        assert(atomic_load(&source_gets)==atomic_load(&source_releases)&&"cancellation must return the QCarCam source");
        unpause_source();atomic_store(&block_encoder,false);
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_encoder,true);pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        wait_frames(&recorders[0],3);
        assert(!shared_stop(0,HUD_STREAMS));
        assert(!encoder.refs&&!next_encoder.refs&&atomic_load(&physical_opens)==1);
        assert(atomic_load(&source_gets)==atomic_load(&source_releases));
        puts("PASS: generation replacement cancels reserved output and releases source ownership");return 0;
    }
    if(test_admission_stop){
        wait_flag(&encoder_entered);
        atomic_store(&pause_sync,true);wait_flag(&sync_entered);
        async_recorder_t *worker=camera->recorder;assert(worker);
        recorder_slot_t *reserved=NULL;
        for(unsigned i=0;i<NBUF;i++)if(atomic_load(&worker->slots[i].state)==REC_WRITING)
            reserved=&worker->slots[i];
        assert(reserved&&"producer must reserve before cached ION sync");
        unsigned uploads=atomic_load(&recorders[0].source_uploads);
        unsigned cancelled=atomic_load(&recorders[0].cancelled_reservations);
        assert(shared_stop(0,HUD_STREAMS)==-1&&"stopping a held capture must report the delayed EGL detach");
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_sync,true);
        pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        struct timespec deadline=capture_stop_deadline();assert(capture_wait_finished(camera,&deadline));
        assert(atomic_load(&recorders[0].source_uploads)==uploads&&
               "stop during sync must cancel a reservation before source GPU upload");
        assert(atomic_load(&recorders[0].cancelled_reservations)>cancelled&&
               atomic_load(&reserved->state)==REC_FREE&&"stop must release its reserved destination");
        assert(atomic_load(&source_gets)==atomic_load(&source_releases));
        atomic_store(&block_encoder,false);
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_encoder,true);pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        assert(!shared_stop(0,HUD_STREAMS));
        assert(!encoder.refs&&atomic_load(&source_gets)==atomic_load(&source_releases));
        puts("PASS: stop during ION sync cancels reservation before source upload");return 0;
    }
    if(test_admission_sync_failure){
        wait_flag(&encoder_entered);wait_frames(&hud_streams[2],3);
        atomic_store(&pause_source,true);wait_flag(&source_paused);
        pthread_mutex_lock(&camera->mutex);
        camera->outputs[2].next_due_ns=0;
        camera->outputs[HUD_STREAMS].next_due_ns=0;
        pthread_mutex_unlock(&camera->mutex);
        unsigned uploads=atomic_load(&recorders[0].source_uploads);
        unsigned cancelled=atomic_load(&recorders[0].cancelled_reservations);
        atomic_store(&fail_sync_once,true);unpause_source();
        struct timespec deadline=capture_stop_deadline();assert(capture_wait_finished(camera,&deadline));
        assert(atomic_load(&recorders[0].source_uploads)==uploads&&
               "failed ION sync must not upload the source");
        assert(atomic_load(&recorders[0].cancelled_reservations)>=cancelled+2&&
               "failed ION sync must release both HUD and recorder reservations");
        assert(atomic_load(&source_gets)==atomic_load(&source_releases));
        atomic_store(&block_encoder,false);
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_encoder,true);pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        assert(!shared_stop(0,HUD_STREAMS));assert(!shared_stop(0,2));
        assert(!encoder.refs&&!hud.refs&&atomic_load(&source_gets)==atomic_load(&source_releases));
        puts("PASS: ION sync failure cancels reservations and releases the vendor source");return 0;
    }
    if(test_recorder_stop_ack){
        wait_flag(&encoder_entered);wait_frames(&hud_streams[2],2);
        unsigned long long before=atomic_load(&hud_streams[2].frames);
        uint64_t began=now_ns();
        Java_com_stasao_gcam_NativeQCarCam_recorderStop(&env,NULL,0);
        assert(atomic_load(&recorder_stop_threw)&&"recorder stop must reject an EGL target still owned by a blocked swap");
        assert(now_ns()-began<1500000000ull&&encoder.refs>0);
        assert(atomic_load(&hud_streams[2].frames)>=before+3&&"waiting for recorder detachment must preserve the live HUD");
        atomic_store(&block_encoder,false);
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_encoder,true);pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        atomic_store(&recorder_stop_threw,false);
        Java_com_stasao_gcam_NativeQCarCam_recorderStop(&env,NULL,0);
        assert(!atomic_load(&recorder_stop_threw)&&!encoder.refs&&"successful recorder stop must confirm release of the old EGL window");
        ANativeWindow replacement={.width=640,.height=400,.recorder=1};
        const char *reply=(const char *)Java_com_stasao_gcam_NativeQCarCam_recorderStart(&env,NULL,(jobject)&replacement,0,25);
        assert(strstr(reply,"Запуск принят"));wait_frames(&recorders[0],3);
        assert(atomic_load(&physical_opens)==1&&"restarting the encoder must reuse the physical source serving HUD");
        Java_com_stasao_gcam_NativeQCarCam_recorderStop(&env,NULL,0);
        assert(!atomic_load(&recorder_stop_threw)&&!replacement.refs);
        assert(!shared_stop(0,2));
        if(camera->recorder){wait_flag(&camera->recorder->exited);async_reap_completed(camera,HUD_STREAMS);}
        began=now_ns();Java_com_stasao_gcam_NativeQCarCam_recorderStop(&env,NULL,0);
        assert(!atomic_load(&recorder_stop_threw)&&now_ns()-began<100000000ull);
        assert(!hud.refs&&atomic_load(&source_gets)==atomic_load(&source_releases));
        puts("PASS: recorder stop confirms EGL detachment, reports timeout and preserves HUD during local restart");return 0;
    }
    if(test_hud_geometry){
        wait_frames(&hud_streams[2],2);
        assert(atomic_load(&hud.geometry_calls)>0&&"primary HUD windows must receive the EGL native pixel format before creation");
        assert(!shared_stop(0,HUD_STREAMS));assert(!shared_stop(0,2));
        if(camera->recorder){wait_flag(&camera->recorder->exited);async_reap_completed(camera,HUD_STREAMS);}
        assert(!hud.refs&&!encoder.refs);
        puts("PASS: primary HUD applies the EGL native window geometry");return 0;
    }
    if(test_hud_failure){
        for(int i=0;i<500&&hud.refs;i++)usleep(10000);
        assert(!hud.refs&&"a failed HUD must release its target after cleanup");
        wait_frames(&hud_streams[6],3);wait_frames(&recorders[0],3);
        for(unsigned i=0;i<2;i++){
            uint64_t began=now_ns();assert(!shared_stop(0,2));
            assert(now_ns()-began<300000000ull&&"a new stop generation must acknowledge an already closed failed target");
        }
        ANativeWindow replacement={.width=320,.height=320};
        const char *reply=(const char *)Java_com_stasao_gcam_NativeQCarCam_hudStart(&env,NULL,2,(jobject)&replacement,0,20,.5f,.5f,1.f,0.f,0);
        assert(strstr(reply,"Запуск принят"));wait_frames(&hud_streams[2],3);
        assert(atomic_load(&physical_opens)==1&&"recovering one failed HUD must share the existing capture");
        uint64_t began=now_ns();assert(!shared_stop(0,2));assert(!shared_stop(0,HUD_STREAMS));assert(!shared_stop(0,6));
        assert(now_ns()-began<300000000ull);
        if(camera->recorder){wait_flag(&camera->recorder->exited);async_reap_completed(camera,HUD_STREAMS);}
        assert(!replacement.refs&&!hud.refs&&!other_hud.refs&&!encoder.refs);
        assert(atomic_load(&source_gets)==atomic_load(&source_releases));
        for(unsigned i=0;i<2;i++){
            began=now_ns();assert(!shared_stop(0,2)&&"stopping a fully closed HUD must acknowledge without a running capture producer");
            assert(now_ns()-began<100000000ull);
        }
        puts("PASS: HUD swap failure preserves peer outputs, repeated stop acknowledgement and public restart");
        return 0;
    }
    if(test_hud_isolation){
        wait_flag(&hud_entered);
        /* Peer EGL programs initialize independently. Measure steady progress
         * after they have produced frames, not their one-time shader setup. */
        wait_frames(&hud_streams[6],3);wait_frames(&recorders[0],3);
        unsigned gets=atomic_load(&source_gets),releases=atomic_load(&source_releases);
        unsigned long long other_frames=atomic_load(&hud_streams[6].frames),encoded=atomic_load(&recorders[0].frames);
        usleep(350000);
        assert(atomic_load(&source_gets)>=gets+3&&"a stalled HUD must not stop physical capture");
        assert(atomic_load(&source_releases)>=releases+3&&"a stalled HUD must not retain camera buffers");
        assert(atomic_load(&hud_streams[6].frames)>=other_frames+3&&"a stalled HUD must not stop another HUD");
        assert(atomic_load(&recorders[0].frames)>=encoded+3&&"a stalled HUD must not stop the encoder");
        assert(!pthread_mutex_trylock(&camera->mutex));pthread_mutex_unlock(&camera->mutex);
        assert(atomic_load(&hud_streams[2].replaced)>0&&"a stalled HUD must replace pending frames in bounded storage");
        unsigned textures=atomic_load(&texture_allocations),contexts=atomic_load(&context_allocations),memory=atomic_load(&next_memory);
        usleep(800000);
        assert(atomic_load(&texture_allocations)==textures&&atomic_load(&context_allocations)==contexts&&atomic_load(&next_memory)==memory&&
               "slow consumers must not grow GPU storage, workers or source buffers");
        unsigned latest_luma=atomic_load(&last_source_luma);
        atomic_store(&block_hud,false);
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_hud,true);pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        for(int i=0;i<500&&!atomic_load(&resumed_hud_luma);i++)usleep(10000);
        assert(atomic_load(&resumed_hud_luma)+6>=latest_luma&&"a resumed HUD must show recent pending pixels instead of a stale FIFO");
        atomic_store(&release_hud,false);atomic_store(&hud_entered,false);atomic_store(&block_hud,true);
        wait_flag(&hud_entered);
        uint64_t began=now_ns();assert(shared_stop(0,2)==-1);
        assert(now_ns()-began<1500000000ull&&hud.refs==1);
        ANativeWindow refused={.width=320,.height=320};
        began=now_ns();
        const char *public_reply=(const char *)Java_com_stasao_gcam_NativeQCarCam_hudStart(&env,NULL,2,(jobject)&refused,0,20,.5f,.5f,1.f,0.f,0);
        assert(!strstr(public_reply,"Запуск принят")&&!refused.refs&&"public HUD start must reject an unacknowledged previous target");
        assert(now_ns()-began<1500000000ull);
        /* The public HUD entry point refuses an unacknowledged old target.
         * The internal desired-state setter can retain one newest replacement;
         * repeated requests must not create another blocked worker/pool. */
        ANativeWindow replacements[100]={0};
        textures=atomic_load(&texture_allocations);contexts=atomic_load(&context_allocations);
        for(unsigned i=0;i<100;i++){
            replacements[i].width=320;replacements[i].height=320;
            began=now_ns();
            assert(strstr((const char *)shared_start(&env,(jobject)&replacements[i],0,2,20),"Запуск принят"));
            assert(now_ns()-began<100000000ull);
        }
        usleep(200000);
        assert(atomic_load(&texture_allocations)==textures&&atomic_load(&context_allocations)==contexts&&atomic_load(&physical_opens)==1&&
               "replacing a blocked target must retain one worker and one physical capture");
        unsigned retained=0,refs=0;
        for(unsigned i=0;i<100;i++){unsigned count=(unsigned)atomic_load(&replacements[i].refs);retained+=count!=0;refs+=count;}
        assert(retained<=2&&refs<=2&&"rapid target replacement must retain only bounded pending window ownership");
        assert(replacements[99].refs&&"the newest requested target must remain available");
        began=now_ns();assert(shared_stop(0,2)==-1);assert(now_ns()-began<1500000000ull);
        began=now_ns();assert(!shared_stop(0,HUD_STREAMS));assert(!shared_stop(0,6));
        assert(now_ns()-began<300000000ull&&other_hud.refs==0);
        assert(atomic_load(&source_gets)==atomic_load(&source_releases)&&"physical stop must release all source frames despite the stalled HUD");
        atomic_store(&block_hud,false);
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_hud,true);pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        for(int i=0;i<500&&hud.refs;i++)usleep(10000);
        if(camera->recorder){wait_flag(&camera->recorder->exited);async_reap_completed(camera,HUD_STREAMS);}
        assert(!hud.refs&&!encoder.refs);
        for(unsigned i=0;i<100;i++)assert(!replacements[i].refs);
        puts("PASS: stalled HUD preserves capture/peer outputs, bounded memory, owned/latest pixels and bounded source stop");
        return 0;
    }
    wait_flag(&encoder_entered);
    if(test_fence_failure){
        async_recorder_t *held=camera->recorder;
        atomic_store(&fail_copy_fence_once,true);wait_flag(&held->stop);
        assert(atomic_load(&held->producer_unsafe));
        unsigned long long before=atomic_load(&hud_streams[2].frames),other_before=atomic_load(&hud_streams[6].frames);usleep(350000);
        assert(atomic_load(&hud_streams[2].frames)>=before+3);
        assert(atomic_load(&hud_streams[6].frames)>=other_before+3);
        ANativeWindow newer={.width=640,.height=400,.recorder=1};
        shared_stop(0,HUD_STREAMS);
        assert(strstr((const char *)shared_start(&env,(jobject)&newer,0,HUD_STREAMS,25),"Запуск принят"));
        atomic_store(&block_encoder,false);
        pthread_mutex_lock(&gate_mutex);atomic_store(&release_encoder,true);pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
        pthread_join(held->thread,NULL);
        assert(camera->recorder==held&&!atomic_load(&held->exited));
        before=atomic_load(&hud_streams[2].frames);usleep(200000);
        assert(atomic_load(&hud_streams[2].frames)>=before+2);
        pthread_mutex_lock(&camera->mutex);
        assert(!camera->outputs[HUD_STREAMS].wanted&&"a permanent GPU quarantine cannot keep an unserved capture alive");
        pthread_mutex_unlock(&camera->mutex);
        uint64_t began=now_ns();shared_stop(0,HUD_STREAMS);shared_stop(0,2);shared_stop(0,6);
        assert(now_ns()-began<1500000000ull);
        assert(!hud.refs&&!other_hud.refs&&!newer.refs&&encoder.refs==1);
        puts("PASS: missing copy fence quarantines one pool, preserves HUD/control and prevents unsafe reuse");
        return 0;
    }
    /* Fill the finite pending pool before checking its steady-state policy;
     * shader initialization and the first GPU copies are not a frame SLA. */
    for(unsigned i=0;i<300&&!atomic_load(&recorders[0].replaced);i++)usleep(10000);
    int locked=pthread_mutex_trylock(&camera->mutex);
    assert(locked==0&&"blocked encoder must not hold the camera-control mutex");
    pthread_mutex_unlock(&camera->mutex);
    unsigned long long before=atomic_load(&hud_streams[2].frames);
    usleep(350000);
    unsigned long long after=atomic_load(&hud_streams[2].frames);
    assert(after>=before+3&&"HUD must keep receiving new frames during encoder backpressure");
    assert(atomic_load(&recorders[0].replaced)>0&&"pending encoder frames must be replaced and counted, not queued");
    assert(!atomic_load(&recorders[0].dropped)&&"encoder backpressure is not a hardware capture timeout");
    /* A non-last HUD stop must acknowledge actual Window release, not just
     * desired state; callers may then dispose their ImageReader/SurfaceTexture. */
    uint64_t hudStopBegan=now_ns();shared_stop(0,2);
    assert(now_ns()-hudStopBegan<300000000ull);
    assert(!hud.refs&&"HUD stop must return only after the producer releases its target");
    assert(strstr((const char *)shared_start(&env,(jobject)&hud,0,2,20),"Запуск принят"));
    /* A blocked swap must produce a bounded detach failure, never a false
     * acknowledgement permitting the codec to release its input Surface. */
    uint64_t began=now_ns();assert(shared_stop(0,HUD_STREAMS)==-1);
    assert(now_ns()-began<1500000000ull);
    assert(!atomic_load(&recorders[0].running));
    ANativeWindow next_encoder={.width=640,.height=400,.recorder=1};
    began=now_ns();
    jstring accepted=shared_start(&env,(jobject)&next_encoder,0,HUD_STREAMS,25);
    assert(strstr((const char *)accepted,"Запуск принят"));
    assert(now_ns()-began<100000000ull);
    assert(atomic_load(&physical_opens)==1&&"sharing must not open a second physical capture");
    /* This target now needs its own EGL worker/program after replacement.
     * Measure uninterrupted progress after its first frames, not shader setup. */
    wait_frames(&hud_streams[2],2);
    before=atomic_load(&hud_streams[2].frames);usleep(350000);
    assert(atomic_load(&hud_streams[2].frames)>=before+3);
    atomic_store(&fail_swap_once,true);
    pthread_mutex_lock(&gate_mutex);atomic_store(&release_encoder,true);pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
    atomic_store(&block_encoder,false);
    wait_frames(&recorders[0],3);
    assert(!encoder.refs&&next_encoder.refs==2);

    /* The worker owns its context/pool while blocked. Stopping the physical
     * source must not join it or release its Surface prematurely; a new source
     * can serve HUD while recording waits for that single old worker. */
    atomic_store(&release_encoder,false);atomic_store(&encoder_entered,false);atomic_store(&block_encoder,true);
    wait_flag(&encoder_entered);
    began=now_ns();assert(shared_stop(0,HUD_STREAMS)==-1);shared_stop(0,2);
    assert(now_ns()-began<1500000000ull);
    assert(!hud.refs&&next_encoder.refs==1);
    assert(strstr((const char *)shared_start(&env,(jobject)&hud,0,2,20),"Запуск принят"));
    ANativeWindow final_encoder={.width=640,.height=400,.recorder=1};
    assert(strstr((const char *)shared_start(&env,(jobject)&final_encoder,0,HUD_STREAMS,25),"Запуск принят"));
    wait_frames(&hud_streams[2],3);
    assert(atomic_load(&physical_opens)==2);
    atomic_store(&block_encoder,false);
    pthread_mutex_lock(&gate_mutex);atomic_store(&release_encoder,true);pthread_cond_broadcast(&gate_cond);pthread_mutex_unlock(&gate_mutex);
    wait_frames(&recorders[0],3);
    assert(!next_encoder.refs&&final_encoder.refs==2);
    shared_stop(0,HUD_STREAMS);shared_stop(0,2);
    if(camera->recorder){wait_flag(&camera->recorder->exited);async_reap_completed(camera,HUD_STREAMS);}
    assert(!hud.refs&&!encoder.refs&&!next_encoder.refs&&!final_encoder.refs);
    puts("PASS: blocked encoder preserves control/HUD, bounded restart, source and GPU ownership");
    return 0;
}
