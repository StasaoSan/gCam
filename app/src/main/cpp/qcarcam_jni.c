#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <EGL/egl.h>
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
#define W 1280u
#define H 800u
#define BYTES (W * H * 2u)
#define NBUF 3u
#define UYVY8 0x07080102u

/* QCarCam 1.0 ABI verified against the G636 qcarcam_hidl_test binary. */
typedef struct { uint32_t width, height, stride, size; int64_t mem_handle; } qplane_t;
typedef struct { qplane_t planes[3]; uint32_t n_planes, reserved; } qbuffer_t;
typedef struct { uint32_t color_fmt, reserved; qbuffer_t *buffers; uint32_t n_buffers, flags; } qbuffers_t;
typedef struct { uint32_t idx, flags; uint64_t timestamp; uint32_t sequence, field; } qframe_t;
typedef void *qhandle_t;
typedef int (*f_init)(void); typedef int (*f_uninit)(void);
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
    unsigned input; char status[512];
} state_t;
static state_t s = { .mutex=PTHREAD_MUTEX_INITIALIZER, .status="Остановлено" };

static uint64_t now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return (uint64_t)t.tv_sec*1000000000ull+t.tv_nsec; }
static void statusf(const char *fmt, ...) {
    va_list ap; pthread_mutex_lock(&s.mutex); va_start(ap,fmt);
    vsnprintf(s.status,sizeof(s.status),fmt,ap); va_end(ap); pthread_mutex_unlock(&s.mutex);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", s.status);
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

typedef struct { EGLDisplay d; EGLSurface s; EGLContext c; GLuint program,texture; GLint sampler; int w,h; } renderer_t;
static GLuint shader(GLenum type,const char *source){
    GLuint x=glCreateShader(type); glShaderSource(x,1,&source,NULL); glCompileShader(x); GLint ok=0; glGetShaderiv(x,GL_COMPILE_STATUS,&ok);
    if(!ok){char log[512];glGetShaderInfoLog(x,sizeof(log),NULL,log);LOGE("shader: %s",log);glDeleteShader(x);return 0;} return x;
}
static int renderer_init(renderer_t *r,ANativeWindow *window){
    memset(r,0,sizeof(*r));r->d=eglGetDisplay(EGL_DEFAULT_DISPLAY);if(r->d==EGL_NO_DISPLAY||!eglInitialize(r->d,NULL,NULL))return -1;
    EGLint attrs[]={EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
    EGLConfig cfg;EGLint count,fmt;if(!eglChooseConfig(r->d,attrs,&cfg,1,&count)||!count)return -1;eglGetConfigAttrib(r->d,cfg,EGL_NATIVE_VISUAL_ID,&fmt);ANativeWindow_setBuffersGeometry(window,0,0,fmt);
    r->s=eglCreateWindowSurface(r->d,cfg,window,NULL);EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};r->c=eglCreateContext(r->d,cfg,EGL_NO_CONTEXT,ca);
    if(r->s==EGL_NO_SURFACE||r->c==EGL_NO_CONTEXT||!eglMakeCurrent(r->d,r->s,r->s,r->c))return -1;eglSwapInterval(r->d,1);eglQuerySurface(r->d,r->s,EGL_WIDTH,&r->w);eglQuerySurface(r->d,r->s,EGL_HEIGHT,&r->h);
    const char *vs="#version 300 es\nconst vec2 p[3]=vec2[3](vec2(-1.,-1.),vec2(3.,-1.),vec2(-1.,3.));out vec2 uv;void main(){gl_Position=vec4(p[gl_VertexID],0.,1.);uv=(p[gl_VertexID]+1.)*.5;}";
    const char *fs="#version 300 es\nprecision highp float;precision highp int;uniform sampler2D packedUyvy;in vec2 uv;out vec4 o;void main(){int x=clamp(int(uv.x*1280.),0,1279);int y=clamp(int((1.-uv.y)*800.),0,799);vec4 q=texelFetch(packedUyvy,ivec2(x/2,y),0);float yy=((x&1)==0?q.g:q.a)*255.;float u=q.r*255.-128.;float v=q.b*255.-128.;yy=1.164*(yy-16.);vec3 c=vec3(yy+1.596*v,yy-.392*u-.813*v,yy+2.017*u)/255.;o=vec4(clamp(c,0.,1.),1.);}";
    GLuint v=shader(GL_VERTEX_SHADER,vs),f=shader(GL_FRAGMENT_SHADER,fs);if(!v||!f)return -1;r->program=glCreateProgram();glAttachShader(r->program,v);glAttachShader(r->program,f);glLinkProgram(r->program);glDeleteShader(v);glDeleteShader(f);GLint linked=0;glGetProgramiv(r->program,GL_LINK_STATUS,&linked);if(!linked)return -1;
    r->sampler=glGetUniformLocation(r->program,"packedUyvy");glGenTextures(1,&r->texture);glBindTexture(GL_TEXTURE_2D,r->texture);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexStorage2D(GL_TEXTURE_2D,1,GL_RGBA8,W/2,H);return glGetError()==GL_NO_ERROR?0:-1;
}
static int draw(renderer_t *r,const void *p){
    glBindTexture(GL_TEXTURE_2D,r->texture);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,W/2,H,GL_RGBA,GL_UNSIGNED_BYTE,p);glUseProgram(r->program);glUniform1i(r->sampler,0);
    int vw=r->w,vh=vw*(int)H/(int)W;if(vh>r->h){vh=r->h;vw=vh*(int)W/(int)H;}glViewport((r->w-vw)/2,(r->h-vh)/2,vw,vh);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);glDrawArrays(GL_TRIANGLES,0,3);return eglSwapBuffers(r->d,r->s)?0:-1;
}
static void renderer_free(renderer_t *r){if(!r->d||r->d==EGL_NO_DISPLAY)return;if(r->texture)glDeleteTextures(1,&r->texture);if(r->program)glDeleteProgram(r->program);eglMakeCurrent(r->d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);if(r->c&&r->c!=EGL_NO_CONTEXT)eglDestroyContext(r->d,r->c);if(r->s&&r->s!=EGL_NO_SURFACE)eglDestroySurface(r->d,r->s);eglTerminate(r->d);}

typedef struct { int fd; void *address; } frame_memory_t;
typedef struct { uint64_t len; uint32_t heap_mask, flags, fd, unused; } ion_alloc_t;
#define ION_IOC_ALLOC_G636 0xc0184900UL

static int alloc_buffers(frame_memory_t *memory,qbuffers_t *q){
    memset(q,0,sizeof(*q));q->color_fmt=UYVY8;q->n_buffers=NBUF;q->buffers=calloc(NBUF,sizeof(qbuffer_t));if(!q->buffers)return -1;
    int ion=open("/dev/ion",O_RDWR|O_CLOEXEC);if(ion<0){LOGE("open /dev/ion: %s",strerror(errno));return -1;}
    for(unsigned i=0;i<NBUF;i++){
        /* Non-secure heap selected by the stock libais_test_util on G636. */
        ion_alloc_t allocation={.len=(BYTES+4095u)&~4095u,.heap_mask=0x02000000,.flags=0,.fd=0};
        if(ioctl(ion,ION_IOC_ALLOC_G636,&allocation)){LOGE("ION_IOC_ALLOC: %s",strerror(errno));close(ion);return -1;}
        memory[i].fd=(int)allocation.fd;
        memory[i].address=mmap(NULL,allocation.len,PROT_READ|PROT_WRITE,MAP_SHARED,memory[i].fd,0);
        if(memory[i].address==MAP_FAILED){LOGE("mmap ION: %s",strerror(errno));memory[i].address=NULL;close(ion);return -1;}
        qbuffer_t *b=&q->buffers[i];b->n_planes=1;b->planes[0]=(qplane_t){W,H,W,BYTES,memory[i].fd};
    }
    close(ion);return 0;
}
static void free_buffers(frame_memory_t *memory,qbuffers_t *q){for(unsigned i=0;i<NBUF;i++){if(memory[i].address)munmap(memory[i].address,(BYTES+4095u)&~4095u);if(memory[i].fd>=0)close(memory[i].fd);}free(q->buffers);memset(q,0,sizeof(*q));}

static void qcarcam_event(qhandle_t handle, int event, void *payload) {
    (void)handle; (void)event; (void)payload;
}

static void *stream_thread(void *unused){
    (void)unused;qapi_t a={0};qhandle_t cam=NULL;frame_memory_t memory[NBUF]={{-1,NULL},{-1,NULL},{-1,NULL}};qbuffers_t qb={0};renderer_t r={.d=EGL_NO_DISPLAY,.s=EGL_NO_SURFACE,.c=EGL_NO_CONTEXT};int inited=0,started=0,rc;char err[384]={0};uint64_t begun=now_ns();
    statusf("Загрузка QCarCam HIDL…");if(load_api(&a,err,sizeof(err))){statusf("QCarCam недоступен: %s",err);goto done;}rc=a.init();if(rc){statusf("qcarcam_initialize: ошибка %d",rc);goto done;}inited=1;
    cam=a.open(s.input);if(!cam){statusf("Не удалось открыть QCarCam input %u",s.input);goto done;}if(alloc_buffers(memory,&qb)){statusf("ION недоступен — запустите tools/prepare_ion_access.sh");goto done;}rc=a.buffers(cam,&qb);if(rc){statusf("qcarcam_s_buffers: ошибка %d",rc);goto done;}
    unsigned char event_value[264]={0}; *(void **)event_value=(void *)qcarcam_event;
    rc=a.param(cam,1,event_value);if(rc){statusf("qcarcam_s_param(callback): ошибка %d",rc);goto done;}
    memset(event_value,0,sizeof(event_value));*(uint32_t *)event_value=15;
    rc=a.param(cam,2,event_value);if(rc){statusf("qcarcam_s_param(mask): ошибка %d",rc);goto done;}
    if(renderer_init(&r,s.window)){statusf("Ошибка GLES/EGL 0x%x",eglGetError());goto done;}rc=a.start(cam);if(rc){statusf("qcarcam_start: ошибка %d",rc);goto done;}started=1;atomic_store(&s.running,true);statusf("QCarCam input %u · ожидание первого кадра…",s.input);
    while(!atomic_load(&s.stop)){qframe_t f={0};rc=a.get(cam,&f,500000000ull,0);if(rc){if(!atomic_load(&s.stop))atomic_fetch_add(&s.dropped,1);continue;}if(f.idx>=NBUF){atomic_fetch_add(&s.dropped,1);a.release(cam,f.idx);continue;}void *pixels=memory[f.idx].address;if(pixels){if(draw(&r,pixels)){a.release(cam,f.idx);statusf("Ошибка EGL; поток остановлен");break;}uint64_t n=atomic_fetch_add(&s.frames,1)+1,now=now_ns();atomic_store(&s.last_ns,now);if(n==1){atomic_store(&s.first_ms,(now-begun)/1000000ull);statusf("QCarCam input %u · поток активен",s.input);}}else atomic_fetch_add(&s.dropped,1);a.release(cam,f.idx);}
done:atomic_store(&s.running,false);if(started)a.stop(cam);renderer_free(&r);free_buffers(memory,&qb);if(cam&&a.close)a.close(cam);if(inited&&a.uninit)a.uninit();if(a.lib)dlclose(a.lib);if(s.window){ANativeWindow_release(s.window);s.window=NULL;}return NULL;
}
static void stop_stream(void){atomic_store(&s.stop,true);pthread_mutex_lock(&s.mutex);int join=s.created;pthread_t t=s.thread;pthread_mutex_unlock(&s.mutex);if(join&&!pthread_equal(pthread_self(),t))pthread_join(t,NULL);pthread_mutex_lock(&s.mutex);s.created=0;pthread_mutex_unlock(&s.mutex);}

JNIEXPORT jstring JNICALL Java_com_stasao_gcam_NativeQCarCam_start(JNIEnv *e,jclass c,jobject surface,jint input){(void)c;stop_stream();if(!surface)return (*e)->NewStringUTF(e,"Surface не создан");s.window=ANativeWindow_fromSurface(e,surface);if(!s.window)return (*e)->NewStringUTF(e,"Не удалось получить ANativeWindow");atomic_store(&s.stop,false);atomic_store(&s.running,false);atomic_store(&s.frames,0);atomic_store(&s.dropped,0);atomic_store(&s.first_ms,0);atomic_store(&s.last_ns,0);s.input=(unsigned)input;statusf("Запуск QCarCam input %d…",input);if(pthread_create(&s.thread,NULL,stream_thread,NULL)){ANativeWindow_release(s.window);s.window=NULL;statusf("Не удалось создать поток захвата");return (*e)->NewStringUTF(e,s.status);}pthread_mutex_lock(&s.mutex);s.created=1;pthread_mutex_unlock(&s.mutex);return (*e)->NewStringUTF(e,"Запуск принят");}
JNIEXPORT void JNICALL Java_com_stasao_gcam_NativeQCarCam_stop(JNIEnv *e,jclass c){(void)e;(void)c;stop_stream();statusf("Остановлено");}
JNIEXPORT jstring JNICALL Java_com_stasao_gcam_NativeQCarCam_status(JNIEnv *e,jclass c){(void)c;char x[512];pthread_mutex_lock(&s.mutex);snprintf(x,sizeof(x),"%s",s.status);pthread_mutex_unlock(&s.mutex);return (*e)->NewStringUTF(e,x);}
JNIEXPORT jlongArray JNICALL Java_com_stasao_gcam_NativeQCarCam_stats(JNIEnv *e,jclass c){(void)c;jlong v[5]={atomic_load(&s.running),(jlong)atomic_load(&s.frames),(jlong)atomic_load(&s.dropped),(jlong)atomic_load(&s.first_ms),(jlong)atomic_load(&s.last_ns)};jlongArray a=(*e)->NewLongArray(e,5);if(a)(*e)->SetLongArrayRegion(e,a,0,5,v);return a;}
JNIEXPORT jstring JNICALL Java_com_stasao_gcam_NativeQCarCam_probe(JNIEnv *e,jclass c){(void)c;qapi_t a;char err[384]={0},out[800];if(load_api(&a,err,sizeof(err))){snprintf(out,sizeof(out),"QCarCam HIDL client: недоступен\n%s",err);return (*e)->NewStringUTF(e,out);}int init=a.init(),query=-1;unsigned count=0;if(!init&&a.query)query=a.query(NULL,0,&count);if(!init)a.uninit();dlclose(a.lib);snprintf(out,sizeof(out),"QCarCam HIDL client: загружен\ninitialize=%d query=%d inputs=%u",init,query,count);return (*e)->NewStringUTF(e,out);}
