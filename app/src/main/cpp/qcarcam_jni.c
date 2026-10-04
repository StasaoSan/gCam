#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#define TAG "gcam_qcarcam"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define W 1280u
#define H 800u
#define STRIDE_BYTES (W * 2u)
#define BYTES (W * H * 2u)
#define NBUF 3u
#define UYVY8 0x07080102u
#define FOURCC_CODE(a,b,c,d) ((uint32_t)(a)|((uint32_t)(b)<<8)|((uint32_t)(c)<<16)|((uint32_t)(d)<<24))
#define DRM_FORMAT_ABGR8888 FOURCC_CODE('A','B','2','4')

#ifndef EGL_LINUX_DMA_BUF_EXT
#define EGL_LINUX_DMA_BUF_EXT 0x3270
#define EGL_LINUX_DRM_FOURCC_EXT 0x3271
#define EGL_DMA_BUF_PLANE0_FD_EXT 0x3272
#define EGL_DMA_BUF_PLANE0_OFFSET_EXT 0x3273
#define EGL_DMA_BUF_PLANE0_PITCH_EXT 0x3274
#endif

/* QCarCam 1.0 ABI verified against the G636 qcarcam_hidl_test binary. */
typedef struct { uint32_t width, height, stride, size; int64_t mem_handle; } qplane_t;
typedef struct { qplane_t planes[3]; uint32_t n_planes, reserved; } qbuffer_t;
typedef struct { uint32_t color_fmt, reserved; qbuffer_t *buffers; uint32_t n_buffers, flags; } qbuffers_t;
typedef struct { uint32_t idx, flags; uint64_t timestamp; uint32_t sequence, field; } qframe_t;
typedef void *qhandle_t;
typedef int (*f_init)(const void *); typedef int (*f_uninit)(void);
typedef int (*f_query)(void *, unsigned int, unsigned int *);
typedef qhandle_t (*f_open)(unsigned int); typedef int (*f_handle)(qhandle_t);
typedef int (*f_buffers)(qhandle_t, qbuffers_t *);
typedef int (*f_param)(qhandle_t, int, void *);
typedef int (*f_get)(qhandle_t, qframe_t *, uint64_t, uint32_t);
typedef int (*f_release)(qhandle_t, unsigned int);
typedef struct {
    void *lib; f_init init; f_uninit uninit; f_query query; f_open open;
    f_handle close, start, stop; f_buffers buffers; f_param param; f_get get; f_release release;
} qapi_t;

typedef struct {
    pthread_mutex_t mutex; pthread_t thread; int created; ANativeWindow *window;
    atomic_bool stop, running; atomic_ullong frames, dropped, first_ms, last_ns;
    unsigned input; int recordable, target_fps; char status[512];
} state_t;
static state_t s = { .mutex=PTHREAD_MUTEX_INITIALIZER, .status="Остановлено" };
static state_t recorders[4];
static pthread_once_t recorders_once=PTHREAD_ONCE_INIT;

static void init_recorders(void){
    for(unsigned i=0;i<4;i++){pthread_mutex_init(&recorders[i].mutex,NULL);recorders[i].input=i;recorders[i].recordable=1;snprintf(recorders[i].status,sizeof(recorders[i].status),"Остановлено");}
}

static uint64_t now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return (uint64_t)t.tv_sec*1000000000ull+t.tv_nsec; }
static void statusf(state_t *state,const char *fmt, ...) {
    va_list ap; pthread_mutex_lock(&state->mutex); va_start(ap,fmt);
    vsnprintf(state->status,sizeof(state->status),fmt,ap); va_end(ap); pthread_mutex_unlock(&state->mutex);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", state->status);
}
static void *sym(void *l,const char *n) { void *p=dlsym(l,n); if(!p) LOGE("dlsym %s: %s",n,dlerror()); return p; }
static int load_api(qapi_t *a,char *err,size_t cap) {
    memset(a,0,sizeof(*a));
    const char *paths[]={"libais_hidl_client.so","/system/lib64/libais_hidl_client.so",NULL};
    for(int i=0;paths[i];i++){ dlerror(); a->lib=dlopen(paths[i],RTLD_NOW|RTLD_LOCAL); if(a->lib)break; const char *e=dlerror(); LOGE("dlopen(%s): %s",paths[i],e?e:"failed"); snprintf(err,cap,"%s",e?e:"dlopen failed"); }
    if(!a->lib)return -1;
    a->init=(f_init)sym(a->lib,"qcarcam_initialize"); a->uninit=(f_uninit)sym(a->lib,"qcarcam_uninitialize");
    a->query=(f_query)sym(a->lib,"qcarcam_query_inputs"); a->open=(f_open)sym(a->lib,"qcarcam_open");
    a->close=(f_handle)sym(a->lib,"qcarcam_close"); a->buffers=(f_buffers)sym(a->lib,"qcarcam_s_buffers");
    a->param=(f_param)sym(a->lib,"qcarcam_s_param");
    a->start=(f_handle)sym(a->lib,"qcarcam_start"); a->stop=(f_handle)sym(a->lib,"qcarcam_stop");
    a->get=(f_get)sym(a->lib,"qcarcam_get_frame"); a->release=(f_release)sym(a->lib,"qcarcam_release_frame");
    if(!a->init||!a->uninit||!a->open||!a->close||!a->buffers||!a->param||!a->start||!a->stop||!a->get||!a->release){ snprintf(err,cap,"неполный QCarCam API"); dlclose(a->lib); memset(a,0,sizeof(*a)); return -1; }
    return 0;
}

static pthread_mutex_t api_mutex=PTHREAD_MUTEX_INITIALIZER;
static qapi_t shared_api;
static int api_loaded=0,api_refs=0;
static int acquire_api(qapi_t *out,char *err,size_t cap){
    pthread_mutex_lock(&api_mutex);
    if(!api_loaded){if(load_api(&shared_api,err,cap)){pthread_mutex_unlock(&api_mutex);return -1;}api_loaded=1;}
    /* G636 ABI accepts an optional init struct. NULL selects the stock defaults. */
    if(api_refs==0){int rc=shared_api.init(NULL);if(rc){snprintf(err,cap,"qcarcam_initialize: ошибка %d",rc);pthread_mutex_unlock(&api_mutex);return -1;}}
    api_refs++;*out=shared_api;pthread_mutex_unlock(&api_mutex);return 0;
}
static void release_api(void){
    pthread_mutex_lock(&api_mutex);if(api_refs>0&&--api_refs==0)shared_api.uninit();pthread_mutex_unlock(&api_mutex);
}

typedef struct { int fd; void *address; } frame_memory_t;
typedef void (*f_image_target_texture)(GLenum target,void *image);

typedef enum { UPLOAD_PBO=0, UPLOAD_DMABUF_RGBA=1 } upload_mode_t;
typedef struct {
    EGLDisplay d; EGLSurface s; EGLContext c; GLuint program,texture; GLint sampler; int w,h;
    GLuint pbo[NBUF],imported_texture[NBUF]; EGLImageKHR image[NBUF]; GLsync fence[NBUF];
    int pbo_upload,recordable; upload_mode_t upload_mode;
    PFNEGLCREATEIMAGEKHRPROC create_image; PFNEGLDESTROYIMAGEKHRPROC destroy_image;
    f_image_target_texture image_target_texture;
    EGLBoolean (*presentation_time)(EGLDisplay,EGLSurface,EGLnsecsANDROID);
} renderer_t;
static GLuint shader(GLenum type,const char *source){
    GLuint x=glCreateShader(type); glShaderSource(x,1,&source,NULL); glCompileShader(x); GLint ok=0; glGetShaderiv(x,GL_COMPILE_STATUS,&ok);
    if(!ok){char log[512];glGetShaderInfoLog(x,sizeof(log),NULL,log);LOGE("shader: %s",log);glDeleteShader(x);return 0;} return x;
}
static int has_extension(const char *extensions,const char *name){
    if(!extensions||!name||!*name||strchr(name,' '))return 0;size_t n=strlen(name);const char *p=extensions;
    while((p=strstr(p,name))){if((p==extensions||p[-1]==' ')&&(p[n]=='\0'||p[n]==' '))return 1;p+=n;}return 0;
}
static void texture_params(void){
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
}
static void discard_dmabuf_import(renderer_t *r){
    for(unsigned i=0;i<NBUF;i++){if(r->fence[i]){glDeleteSync(r->fence[i]);r->fence[i]=0;}}
    if(r->imported_texture[0])glDeleteTextures(NBUF,r->imported_texture);
    memset(r->imported_texture,0,sizeof(r->imported_texture));
    if(r->destroy_image)for(unsigned i=0;i<NBUF;i++){if(r->image[i]&&r->image[i]!=EGL_NO_IMAGE_KHR)r->destroy_image(r->d,r->image[i]);}
    memset(r->image,0,sizeof(r->image));r->upload_mode=UPLOAD_PBO;
}
static int try_dmabuf_import(renderer_t *r,frame_memory_t *memory){
    const char *egl_extensions=eglQueryString(r->d,EGL_EXTENSIONS),*gl_extensions=(const char *)glGetString(GL_EXTENSIONS);
    if(!has_extension(egl_extensions,"EGL_EXT_image_dma_buf_import")||!has_extension(gl_extensions,"GL_OES_EGL_image")){LOGE("zero-copy unavailable: required EGL/GL extensions missing");return -1;}
    r->create_image=(PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");r->destroy_image=(PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");r->image_target_texture=(f_image_target_texture)eglGetProcAddress("glEGLImageTargetTexture2DOES");
    if(!r->create_image||!r->destroy_image||!r->image_target_texture){LOGE("zero-copy unavailable: EGLImage entry points missing");return -1;}
    glGenTextures(NBUF,r->imported_texture);
    for(unsigned i=0;i<NBUF;i++){
        EGLint image_attrs[]={EGL_WIDTH,(EGLint)(W/2),EGL_HEIGHT,(EGLint)H,EGL_LINUX_DRM_FOURCC_EXT,(EGLint)DRM_FORMAT_ABGR8888,EGL_DMA_BUF_PLANE0_FD_EXT,memory[i].fd,EGL_DMA_BUF_PLANE0_OFFSET_EXT,0,EGL_DMA_BUF_PLANE0_PITCH_EXT,(EGLint)STRIDE_BYTES,EGL_NONE};
        r->image[i]=r->create_image(r->d,EGL_NO_CONTEXT,EGL_LINUX_DMA_BUF_EXT,(EGLClientBuffer)0,image_attrs);
        if(r->image[i]==EGL_NO_IMAGE_KHR){LOGE("zero-copy EGLImage[%u] failed: 0x%x",i,eglGetError());discard_dmabuf_import(r);return -1;}
        glBindTexture(GL_TEXTURE_2D,r->imported_texture[i]);texture_params();r->image_target_texture(GL_TEXTURE_2D,(void *)r->image[i]);
        GLenum error=glGetError();if(error!=GL_NO_ERROR){LOGE("zero-copy texture[%u] failed: 0x%x",i,error);discard_dmabuf_import(r);return -1;}
    }
    r->upload_mode=UPLOAD_DMABUF_RGBA;LOGI("zero-copy enabled: ION DMA-BUF -> EGLImage ABGR8888");return 0;
}
static int init_pbo(renderer_t *r){
    glGenTextures(1,&r->texture);glBindTexture(GL_TEXTURE_2D,r->texture);texture_params();glTexStorage2D(GL_TEXTURE_2D,1,GL_RGBA8,W/2,H);
    glGenBuffers(NBUF,r->pbo);for(unsigned i=0;i<NBUF;i++){glBindBuffer(GL_PIXEL_UNPACK_BUFFER,r->pbo[i]);glBufferData(GL_PIXEL_UNPACK_BUFFER,BYTES,NULL,GL_STREAM_DRAW);}glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
    r->pbo_upload=glGetError()==GL_NO_ERROR;r->upload_mode=UPLOAD_PBO;return r->pbo_upload?0:-1;
}
static int renderer_init(renderer_t *r,ANativeWindow *window,int recordable,frame_memory_t *memory){
    memset(r,0,sizeof(*r));r->d=eglGetDisplay(EGL_DEFAULT_DISPLAY);if(r->d==EGL_NO_DISPLAY||!eglInitialize(r->d,NULL,NULL))return -1;
    EGLint attrs[]={EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_RECORDABLE_ANDROID,recordable?EGL_TRUE:EGL_DONT_CARE,EGL_NONE};
    EGLConfig cfg;EGLint count,fmt;if(!eglChooseConfig(r->d,attrs,&cfg,1,&count)||!count)return -1;eglGetConfigAttrib(r->d,cfg,EGL_NATIVE_VISUAL_ID,&fmt);if(!recordable)ANativeWindow_setBuffersGeometry(window,0,0,fmt);
    r->s=eglCreateWindowSurface(r->d,cfg,window,NULL);EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};r->c=eglCreateContext(r->d,cfg,EGL_NO_CONTEXT,ca);
    if(r->s==EGL_NO_SURFACE||r->c==EGL_NO_CONTEXT||!eglMakeCurrent(r->d,r->s,r->s,r->c))return -1;r->recordable=recordable;r->presentation_time=(void *)eglGetProcAddress("eglPresentationTimeANDROID");eglSwapInterval(r->d,recordable?0:1);eglQuerySurface(r->d,r->s,EGL_WIDTH,&r->w);eglQuerySurface(r->d,r->s,EGL_HEIGHT,&r->h);
    const char *vs="#version 300 es\nconst vec2 p[3]=vec2[3](vec2(-1.,-1.),vec2(3.,-1.),vec2(-1.,3.));out vec2 uv;void main(){gl_Position=vec4(p[gl_VertexID],0.,1.);uv=(p[gl_VertexID]+1.)*.5;}";
    const char *fs="#version 300 es\nprecision highp float;precision highp int;uniform sampler2D packedUyvy;in vec2 uv;out vec4 o;void main(){int x=clamp(int(uv.x*1280.),0,1279);int y=clamp(int((1.-uv.y)*800.),0,799);vec4 q=texelFetch(packedUyvy,ivec2(x/2,y),0);float yy=((x&1)==0?q.g:q.a)*255.;float u=q.r*255.-128.;float v=q.b*255.-128.;yy=1.164*(yy-16.);vec3 c=vec3(yy+1.596*v,yy-.392*u-.813*v,yy+2.017*u)/255.;o=vec4(clamp(c,0.,1.),1.);}";
    GLuint v=shader(GL_VERTEX_SHADER,vs),f=shader(GL_FRAGMENT_SHADER,fs);if(!v||!f)return -1;r->program=glCreateProgram();glAttachShader(r->program,v);glAttachShader(r->program,f);glLinkProgram(r->program);glDeleteShader(v);glDeleteShader(f);GLint linked=0;glGetProgramiv(r->program,GL_LINK_STATUS,&linked);if(!linked)return -1;
    r->sampler=glGetUniformLocation(r->program,"packedUyvy");
    if(recordable&&try_dmabuf_import(r,memory)==0)return 0;
    LOGE("using PBO upload fallback");return init_pbo(r);
}
static int draw(renderer_t *r,unsigned idx,const void *p){
    if(r->upload_mode==UPLOAD_DMABUF_RGBA){glBindTexture(GL_TEXTURE_2D,r->imported_texture[idx]);}
    else {glBindTexture(GL_TEXTURE_2D,r->texture);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,r->pbo[idx]);
        void *staging=glMapBufferRange(GL_PIXEL_UNPACK_BUFFER,0,BYTES,GL_MAP_WRITE_BIT|GL_MAP_INVALIDATE_BUFFER_BIT);if(!staging)return -1;memcpy(staging,p,BYTES);if(!glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER))return -1;
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,W/2,H,GL_RGBA,GL_UNSIGNED_BYTE,0);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
    }
    glUseProgram(r->program);glUniform1i(r->sampler,0);
    int vw=r->w,vh=vw*(int)H/(int)W;if(vh>r->h){vh=r->h;vw=vh*(int)W/(int)H;}glViewport((r->w-vw)/2,(r->h-vh)/2,vw,vh);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);glDrawArrays(GL_TRIANGLES,0,3);
    if(r->upload_mode==UPLOAD_DMABUF_RGBA){r->fence[idx]=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);if(!r->fence[idx])return -1;glFlush();}
    if(r->recordable&&r->presentation_time)r->presentation_time(r->d,r->s,(EGLnsecsANDROID)now_ns());return eglSwapBuffers(r->d,r->s)?0:-1;
}
static void renderer_free(renderer_t *r){if(!r->d||r->d==EGL_NO_DISPLAY)return;discard_dmabuf_import(r);if(r->pbo[0])glDeleteBuffers(NBUF,r->pbo);if(r->texture)glDeleteTextures(1,&r->texture);if(r->program)glDeleteProgram(r->program);eglMakeCurrent(r->d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);if(r->c&&r->c!=EGL_NO_CONTEXT)eglDestroyContext(r->d,r->c);if(r->s&&r->s!=EGL_NO_SURFACE)eglDestroySurface(r->d,r->s);/* EGLDisplay is process-global and may still be used by other camera encoders. */}

typedef struct { uint64_t len; uint32_t heap_mask, flags, fd, unused; } ion_alloc_t;
#define ION_IOC_ALLOC_G636 0xc0184900UL

static int alloc_buffers(frame_memory_t *memory,qbuffers_t *q){
    memset(q,0,sizeof(*q));q->color_fmt=UYVY8;q->n_buffers=NBUF;q->buffers=calloc(NBUF,sizeof(qbuffer_t));if(!q->buffers)return -1;
    /* ION control ioctls only require a readable control fd. The returned DMA
       buffer fds remain read/write, matching Android's stock libion behavior. */
    int ion=open("/dev/ion",O_RDONLY|O_CLOEXEC);if(ion<0){LOGE("open /dev/ion: %s",strerror(errno));return -1;}
    for(unsigned i=0;i<NBUF;i++){
        /* Non-secure heap selected by the stock libais_test_util on G636. */
        ion_alloc_t allocation={.len=(BYTES+4095u)&~4095u,.heap_mask=0x02000000,.flags=0,.fd=0};
        if(ioctl(ion,ION_IOC_ALLOC_G636,&allocation)){LOGE("ION_IOC_ALLOC: %s",strerror(errno));close(ion);return -1;}
        memory[i].fd=(int)allocation.fd;
        memory[i].address=mmap(NULL,allocation.len,PROT_READ|PROT_WRITE,MAP_SHARED,memory[i].fd,0);
        if(memory[i].address==MAP_FAILED){LOGE("mmap ION: %s",strerror(errno));memory[i].address=NULL;close(ion);return -1;}
        /* QCarCam's UYVY plane stride is expressed in bytes, not pixels. */
        qbuffer_t *b=&q->buffers[i];b->n_planes=1;b->planes[0]=(qplane_t){W,H,STRIDE_BYTES,BYTES,memory[i].fd};
    }
    close(ion);return 0;
}
static void free_buffers(frame_memory_t *memory,qbuffers_t *q){for(unsigned i=0;i<NBUF;i++){if(memory[i].address)munmap(memory[i].address,(BYTES+4095u)&~4095u);if(memory[i].fd>=0)close(memory[i].fd);}free(q->buffers);memset(q,0,sizeof(*q));}

static void qcarcam_event(qhandle_t handle, int event, void *payload) {
    (void)handle; (void)event; (void)payload;
}

static unsigned pending_fences(renderer_t *r){unsigned count=0;for(unsigned i=0;i<NBUF;i++)if(r->fence[i])count++;return count;}
static int release_completed_frames(renderer_t *r,qapi_t *api,qhandle_t cam,int wait_for_one){
    int released=0;
    for(unsigned i=0;i<NBUF;i++){
        if(!r->fence[i])continue;
        GLbitfield flags=wait_for_one&&!released?GL_SYNC_FLUSH_COMMANDS_BIT:0;
        GLuint64 timeout=wait_for_one&&!released?1000000000ull:0;
        GLenum result=glClientWaitSync(r->fence[i],flags,timeout);
        if(result==GL_ALREADY_SIGNALED||result==GL_CONDITION_SATISFIED){glDeleteSync(r->fence[i]);r->fence[i]=0;api->release(cam,i);released++;}
        else if(result==GL_WAIT_FAILED){LOGE("zero-copy fence[%u] wait failed: 0x%x",i,glGetError());return -1;}
        else if(wait_for_one&&result==GL_TIMEOUT_EXPIRED)return 0;
    }
    return released;
}
static void release_all_frames(renderer_t *r,qapi_t *api,qhandle_t cam){
    if(r->upload_mode!=UPLOAD_DMABUF_RGBA)return;glFinish();
    for(unsigned i=0;i<NBUF;i++){if(r->fence[i]){glDeleteSync(r->fence[i]);r->fence[i]=0;api->release(cam,i);}}
}

static void *stream_thread(void *arg){
    state_t *state=(state_t *)arg;qapi_t a={0};qhandle_t cam=NULL;frame_memory_t memory[NBUF]={{-1,NULL},{-1,NULL},{-1,NULL}};qbuffers_t qb={0};renderer_t r={.d=EGL_NO_DISPLAY,.s=EGL_NO_SURFACE,.c=EGL_NO_CONTEXT};int acquired=0,started=0,rc;char err[384]={0};uint64_t begun=now_ns();
    uint64_t next_frame_ns=0,frame_interval_ns=state->target_fps>0?1000000000ull/(uint64_t)state->target_fps:0;
    statusf(state,"Загрузка QCarCam HIDL…");if(acquire_api(&a,err,sizeof(err))){statusf(state,"QCarCam недоступен: %s",err);goto done;}acquired=1;
    cam=a.open(state->input);if(!cam){statusf(state,"Не удалось открыть QCarCam input %u",state->input);goto done;}if(alloc_buffers(memory,&qb)){statusf(state,"ION недоступен");goto done;}rc=a.buffers(cam,&qb);if(rc){statusf(state,"qcarcam_s_buffers: ошибка %d",rc);goto done;}
    unsigned char event_value[264]={0};*(void **)event_value=(void *)qcarcam_event;
    rc=a.param(cam,1,event_value);if(rc){statusf(state,"qcarcam_s_param(callback): ошибка %d",rc);goto done;}
    memset(event_value,0,sizeof(event_value));*(uint32_t *)event_value=15;
    rc=a.param(cam,2,event_value);if(rc){statusf(state,"qcarcam_s_param(mask): ошибка %d",rc);goto done;}
    if(renderer_init(&r,state->window,state->recordable,memory)){statusf(state,"Ошибка GLES/EGL 0x%x",eglGetError());goto done;}rc=a.start(cam);if(rc){statusf(state,"qcarcam_start: ошибка %d",rc);goto done;}started=1;atomic_store(&state->running,true);statusf(state,"QCarCam input %u · ожидание первого кадра…",state->input);
    while(!atomic_load(&state->stop)){
        if(r.upload_mode==UPLOAD_DMABUF_RGBA){if(release_completed_frames(&r,&a,cam,0)<0)break;if(pending_fences(&r)>=NBUF&&release_completed_frames(&r,&a,cam,1)<=0){statusf(state,"GPU fence timeout; поток остановлен");break;}}
        qframe_t f={0};rc=a.get(cam,&f,500000000ull,0);if(rc){if(!atomic_load(&state->stop))atomic_fetch_add(&state->dropped,1);continue;}if(f.idx>=NBUF){atomic_fetch_add(&state->dropped,1);a.release(cam,f.idx);continue;}
        uint64_t frame_now=now_ns();
        if(state->recordable&&frame_interval_ns){
            if(next_frame_ns&&frame_now<next_frame_ns){a.release(cam,f.idx);continue;}
            if(!next_frame_ns||frame_now>next_frame_ns+frame_interval_ns)next_frame_ns=frame_now+frame_interval_ns;else next_frame_ns+=frame_interval_ns;
        }
        void *pixels=memory[f.idx].address;if(pixels){if(draw(&r,f.idx,pixels)){if(r.upload_mode==UPLOAD_DMABUF_RGBA){glFinish();if(r.fence[f.idx]){glDeleteSync(r.fence[f.idx]);r.fence[f.idx]=0;}}a.release(cam,f.idx);if(!atomic_load(&state->stop))statusf(state,"Ошибка EGL; поток остановлен");break;}uint64_t n=atomic_fetch_add(&state->frames,1)+1,now=now_ns();atomic_store(&state->last_ns,now);if(n==1){atomic_store(&state->first_ms,(now-begun)/1000000ull);statusf(state,"QCarCam input %u · поток активен · %s",state->input,r.upload_mode==UPLOAD_DMABUF_RGBA?"zero-copy":"PBO");}}else {atomic_fetch_add(&state->dropped,1);a.release(cam,f.idx);continue;}if(r.upload_mode==UPLOAD_PBO)a.release(cam,f.idx);}
done:atomic_store(&state->running,false);if(started){release_all_frames(&r,&a,cam);a.stop(cam);}if(cam&&a.close)a.close(cam);renderer_free(&r);free_buffers(memory,&qb);if(acquired)release_api();if(state->window){ANativeWindow_release(state->window);state->window=NULL;}return NULL;
}
static void stop_state(state_t *state){atomic_store(&state->stop,true);pthread_mutex_lock(&state->mutex);int join=state->created;pthread_t t=state->thread;pthread_mutex_unlock(&state->mutex);if(join&&!pthread_equal(pthread_self(),t))pthread_join(t,NULL);pthread_mutex_lock(&state->mutex);state->created=0;pthread_mutex_unlock(&state->mutex);}
static jstring start_state(JNIEnv *e,state_t *state,jobject surface,jint input,int recordable,int target_fps){
    stop_state(state);if(!surface)return (*e)->NewStringUTF(e,"Surface не создан");state->window=ANativeWindow_fromSurface(e,surface);if(!state->window)return (*e)->NewStringUTF(e,"Не удалось получить ANativeWindow");atomic_store(&state->stop,false);atomic_store(&state->running,false);atomic_store(&state->frames,0);atomic_store(&state->dropped,0);atomic_store(&state->first_ms,0);atomic_store(&state->last_ns,0);state->input=(unsigned)input;state->recordable=recordable;state->target_fps=target_fps;statusf(state,"Запуск QCarCam input %d…",input);if(pthread_create(&state->thread,NULL,stream_thread,state)){ANativeWindow_release(state->window);state->window=NULL;statusf(state,"Не удалось создать поток захвата");return (*e)->NewStringUTF(e,state->status);}pthread_mutex_lock(&state->mutex);state->created=1;pthread_mutex_unlock(&state->mutex);return (*e)->NewStringUTF(e,"Запуск принят");
}
static jlongArray state_stats(JNIEnv *e,state_t *state){jlong v[5]={atomic_load(&state->running),(jlong)atomic_load(&state->frames),(jlong)atomic_load(&state->dropped),(jlong)atomic_load(&state->first_ms),(jlong)atomic_load(&state->last_ns)};jlongArray a=(*e)->NewLongArray(e,5);if(a)(*e)->SetLongArrayRegion(e,a,0,5,v);return a;}

JNIEXPORT jstring JNICALL Java_com_stasao_gcam_NativeQCarCam_start(JNIEnv *e,jclass c,jobject surface,jint input){(void)c;return start_state(e,&s,surface,input,0,25);}
JNIEXPORT void JNICALL Java_com_stasao_gcam_NativeQCarCam_stop(JNIEnv *e,jclass c){(void)e;(void)c;stop_state(&s);statusf(&s,"Остановлено");}
JNIEXPORT jstring JNICALL Java_com_stasao_gcam_NativeQCarCam_status(JNIEnv *e,jclass c){(void)c;char x[512];pthread_mutex_lock(&s.mutex);snprintf(x,sizeof(x),"%s",s.status);pthread_mutex_unlock(&s.mutex);return (*e)->NewStringUTF(e,x);}
JNIEXPORT jlongArray JNICALL Java_com_stasao_gcam_NativeQCarCam_stats(JNIEnv *e,jclass c){(void)c;return state_stats(e,&s);}
JNIEXPORT jstring JNICALL Java_com_stasao_gcam_NativeQCarCam_recorderStart(JNIEnv *e,jclass c,jobject surface,jint input,jint target_fps){(void)c;pthread_once(&recorders_once,init_recorders);if(input<0||input>3)return (*e)->NewStringUTF(e,"Некорректный input");if(target_fps<1)target_fps=1;if(target_fps>25)target_fps=25;return start_state(e,&recorders[input],surface,input,1,target_fps);}
JNIEXPORT void JNICALL Java_com_stasao_gcam_NativeQCarCam_recorderStop(JNIEnv *e,jclass c,jint input){(void)e;(void)c;pthread_once(&recorders_once,init_recorders);if(input>=0&&input<4){stop_state(&recorders[input]);statusf(&recorders[input],"Остановлено");}}
JNIEXPORT jstring JNICALL Java_com_stasao_gcam_NativeQCarCam_recorderStatus(JNIEnv *e,jclass c,jint input){(void)c;pthread_once(&recorders_once,init_recorders);if(input<0||input>3)return (*e)->NewStringUTF(e,"Некорректный input");char x[512];pthread_mutex_lock(&recorders[input].mutex);snprintf(x,sizeof(x),"%s",recorders[input].status);pthread_mutex_unlock(&recorders[input].mutex);return (*e)->NewStringUTF(e,x);}
JNIEXPORT jlongArray JNICALL Java_com_stasao_gcam_NativeQCarCam_recorderStats(JNIEnv *e,jclass c,jint input){(void)c;pthread_once(&recorders_once,init_recorders);if(input<0||input>3){state_t empty={0};return state_stats(e,&empty);}return state_stats(e,&recorders[input]);}
JNIEXPORT jstring JNICALL Java_com_stasao_gcam_NativeQCarCam_probe(JNIEnv *e,jclass c){(void)c;qapi_t a;char err[384]={0},out[800];if(acquire_api(&a,err,sizeof(err))){snprintf(out,sizeof(out),"QCarCam HIDL client: недоступен\n%s",err);return (*e)->NewStringUTF(e,out);}int query=-1;unsigned count=0;if(a.query)query=a.query(NULL,0,&count);release_api();snprintf(out,sizeof(out),"QCarCam HIDL client: загружен\nquery=%d inputs=%u",query,count);return (*e)->NewStringUTF(e,out);}
