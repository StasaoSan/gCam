/* Every output owns a shared EGL context and three packed-UYVY textures, never
 * QCarCam/ION source buffers. Capture only copies into an available slot and
 * publishes a fence. A blocked window can retain this one bounded worker/pool;
 * stop/start never accumulates replacement threads or frees an in-flight image. */
enum { REC_FREE,REC_WRITING,REC_READY,REC_READING,REC_RETIRED };
typedef struct {
    atomic_int state;
    GLuint texture;
    GLsync ready,finished;
    uint64_t timestamp,generation,get_ns,requested_ns;
    int upload_mode;
    draw_metrics_t upload;
} recorder_slot_t;
typedef struct async_recorder {
    pthread_t thread;
    pthread_mutex_t mutex;
    pthread_cond_t wake;
    atomic_bool stop,producer_done,exited,failed;
    atomic_bool producer_unsafe;
    atomic_uint_fast64_t failed_generation;
    shared_camera_t *camera;
    unsigned input;
    int output;
    int wanted;
    uint64_t generation,requested_ns;
    ANativeWindow *pending;
    renderer_t renderer;
    recorder_slot_t slots[NBUF];
} async_recorder_t;
typedef struct {
    async_recorder_t *worker;
    recorder_slot_t *slot;
    uint64_t generation,requested_ns;
    int output;
} recorder_reservation_t;
enum { ADMISSION_CANCELLED,ADMISSION_NO_CAPACITY,ADMISSION_RESERVED };

static async_recorder_t *async_worker(shared_camera_t *camera,int output){
    return output==HUD_STREAMS?camera->recorder:camera->hud_workers[output];
}
static void async_set_worker(shared_camera_t *camera,int output,async_recorder_t *worker){
    if(output==HUD_STREAMS)camera->recorder=worker;else camera->hud_workers[output]=worker;
}
static void async_ack_closed(async_recorder_t *worker,uint64_t generation){
    pthread_mutex_lock(&worker->camera->mutex);
    shared_output_t *desired=&worker->camera->outputs[worker->output];
    if(desired->closed_generation<generation)desired->closed_generation=generation;
    pthread_cond_broadcast(&worker->camera->outputs_changed);
    pthread_mutex_unlock(&worker->camera->mutex);
}
static void recorder_wake(async_recorder_t *worker){
    pthread_mutex_lock(&worker->mutex);pthread_cond_signal(&worker->wake);pthread_mutex_unlock(&worker->mutex);
}
/* Called only by the producer after its last possible write. A consumer error
 * first sets stop, then waits for this acknowledgement before deleting objects. */
static void recorder_detach_producer(async_recorder_t *worker){
    if(!worker)return;
    atomic_store(&worker->stop,true);atomic_store_explicit(&worker->producer_done,true,memory_order_release);
    recorder_wake(worker);
}
static void recorder_delete_ready(recorder_slot_t *slot){
    if(slot->ready){glDeleteSync(slot->ready);slot->ready=NULL;}
    atomic_store_explicit(&slot->state,REC_FREE,memory_order_release);
}
static recorder_slot_t *recorder_take_latest(async_recorder_t *worker,uint64_t generation){
    recorder_slot_t *latest=NULL;
    for(unsigned i=0;i<NBUF;i++){
        recorder_slot_t *slot=&worker->slots[i];
        int expected=REC_READY;
        if(!atomic_compare_exchange_strong_explicit(&slot->state,&expected,REC_READING,
                memory_order_acquire,memory_order_relaxed))continue;
        if(slot->generation!=generation){recorder_delete_ready(slot);continue;}
        if(latest&&latest->timestamp>=slot->timestamp){recorder_delete_ready(slot);continue;}
        if(latest)recorder_delete_ready(latest);
        latest=slot;
    }
    return latest;
}
static void recorder_complete_stats(async_recorder_t *worker,shared_output_t *out,
        recorder_slot_t *slot,const draw_metrics_t *timing,recorder_pressure_t *pressure){
    state_t *stats=out->stats;uint64_t completed=now_ns();
    if(worker->output==HUD_STREAMS){
        recorder_pressure_update(pressure,completed,timing->swap_ns);
        atomic_store(&recorder_slow_until[worker->input],pressure->slow_until);
        atomic_store(&recorder_severe_until[worker->input],pressure->severe_until);
    }
    metric_add(&stats->get_total_ns,&stats->get_max_ns,slot->get_ns);atomic_fetch_add(&stats->get_count,1);
    if(slot->upload.memcpy_ns){metric_add(&stats->memcpy_total_ns,&stats->memcpy_max_ns,slot->upload.memcpy_ns);atomic_fetch_add(&stats->memcpy_count,1);}
    metric_add(&stats->upload_total_ns,&stats->upload_max_ns,slot->upload.upload_ns);atomic_fetch_add(&stats->upload_count,1);
    metric_add(&stats->draw_total_ns,&stats->draw_max_ns,timing->draw_ns);atomic_fetch_add(&stats->draw_count,1);
    metric_add(&stats->swap_total_ns,&stats->swap_max_ns,timing->swap_ns);atomic_fetch_add(&stats->swap_count,1);
    uint64_t previous=atomic_load(&stats->last_ns);
    if(previous)metric_max(&stats->gap_max_ns,slot->timestamp-previous);
    uint64_t count=atomic_fetch_add(&stats->frames,1)+1;
    atomic_store(&stats->last_ns,slot->timestamp);atomic_store(&stats->running,true);
    atomic_store(&stats->zero_copy,slot->upload_mode==UPLOAD_DMABUF_RGBA);
    atomic_store(&stats->active_upload_mode,slot->upload_mode);
    if(count==1){
        atomic_store(&stats->first_ms,(completed-slot->requested_ns)/1000000ull);
        statusf(stats,worker->output==HUD_STREAMS?
                "QCarCam input %u · независимый вывод записи активен":
                "QCarCam input %u · независимый вывод камеры активен",worker->input);
    }
}
/* The native owner is released only after EGL proves the window is detached.
 * A failed driver call leaves this worker responsible for retrying cleanup. */
static int recorder_close_window(renderer_t *renderer,shared_output_t *out){
    if(out->surface&&out->surface!=EGL_NO_SURFACE){
        if(!renderer_make_current(renderer,renderer->s,NULL))return -1;
        output_phase_set(renderer->diagnostic,OUTPUT_DETACH,renderer->diagnostic_generation);
        if(!eglDestroySurface(renderer->d,out->surface)){
            output_egl_failure(renderer->diagnostic);return -1;
        }
    }
    out->surface=EGL_NO_SURFACE;
    if(out->window)ANativeWindow_release(out->window);out->window=NULL;
    output_phase_set(renderer->diagnostic,OUTPUT_IDLE,0);
    return 0;
}
static void *recorder_output_thread(void *argument){
    async_recorder_t *worker=argument;renderer_t *renderer=&worker->renderer;
    shared_output_t out={.stats=worker->camera->outputs[worker->output].stats,.surface=EGL_NO_SURFACE};
    recorder_pressure_t pressure={0};
    pthread_mutex_lock(&worker->mutex);uint64_t generation=worker->generation;pthread_mutex_unlock(&worker->mutex);
    int error=0;
    if(!renderer_make_current(renderer,renderer->s,NULL)||renderer_program(renderer))error=1;
    while(!error&&!atomic_load(&worker->stop)){
        pthread_mutex_lock(&worker->mutex);
        uint64_t requested_generation=worker->generation;
        ANativeWindow *pending=worker->pending;worker->pending=NULL;
        int wanted=worker->wanted;
        pthread_mutex_unlock(&worker->mutex);
        if(pending||generation!=requested_generation||!wanted){
            if(recorder_close_window(renderer,&out)){
                if(pending)ANativeWindow_release(pending);
                error=1;break;
            }
            generation=requested_generation;renderer->diagnostic_generation=generation;
            async_ack_closed(worker,generation);
            if(pending&&wanted){
                output_diagnostics_reset(out.stats);
                if(worker->output<4){
                    EGLint format=0;eglGetConfigAttrib(renderer->d,renderer->cfg,EGL_NATIVE_VISUAL_ID,&format);
                    ANativeWindow_setBuffersGeometry(pending,0,0,format);
                }
                output_phase_set(out.stats,OUTPUT_BIND,generation);
                out.window=pending;out.surface=eglCreateWindowSurface(renderer->d,renderer->cfg,pending,NULL);
                if(out.surface==EGL_NO_SURFACE){output_egl_failure(out.stats);error=1;break;}
                eglQuerySurface(renderer->d,out.surface,EGL_WIDTH,&out.width);
                eglQuerySurface(renderer->d,out.surface,EGL_HEIGHT,&out.height);
                output_phase_set(out.stats,OUTPUT_IDLE,0);
            }else if(pending)ANativeWindow_release(pending);
        }
        recorder_slot_t *slot=recorder_take_latest(worker,generation);
        if(!slot||!wanted||!out.window){
            if(slot)recorder_delete_ready(slot);
            pthread_mutex_lock(&worker->mutex);
            if(!atomic_load(&worker->stop)){
                /* A missed wake costs at most one small poll, never a frame queue. */
                struct timespec deadline;clock_gettime(CLOCK_REALTIME,&deadline);
                deadline.tv_nsec+=10000000;
                if(deadline.tv_nsec>=1000000000){deadline.tv_sec++;deadline.tv_nsec-=1000000000;}
                pthread_cond_timedwait(&worker->wake,&worker->mutex,&deadline);
            }
            pthread_mutex_unlock(&worker->mutex);continue;
        }
        /* The owner context remains on this output between frames. GL sync and
         * texture binding work on the current window as well as the pbuffer. */
        uint64_t cpu_start=thread_cpu_ns();
        renderer->diagnostic_generation=slot->generation;
        output_phase_set(out.stats,OUTPUT_WAIT_SYNC,slot->generation);
        glWaitSync(slot->ready,0,GL_TIMEOUT_IGNORED);glDeleteSync(slot->ready);slot->ready=NULL;
        glBindTexture(GL_TEXTURE_2D,slot->texture);
        draw_metrics_t timing={0};
        if(glGetError()!=GL_NO_ERROR||shared_draw(renderer,&out,slot->timestamp,&timing))error=1;
        output_phase_set(out.stats,OUTPUT_FENCE_SUBMIT,slot->generation);
        slot->finished=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);glFlush();
        if(!slot->finished||glGetError()!=GL_NO_ERROR)error=1;
        uint64_t cpu_end=thread_cpu_ns();
        if(cpu_start&&cpu_end>=cpu_start){
            atomic_fetch_add(&out.stats->output_cpu_total_ns,cpu_end-cpu_start);
            atomic_fetch_add(&out.stats->output_cpu_count,1);
        }
        /* Neither an old Surface nor a completed call may revive a cancelled
         * recording generation after stop/reconfiguration. */
        pthread_mutex_lock(&worker->camera->mutex);
        shared_output_t *desired=&worker->camera->outputs[worker->output];
        if(!error&&!atomic_load(&worker->stop)&&desired->wanted&&desired->generation==slot->generation)
            recorder_complete_stats(worker,&out,slot,&timing,&pressure);
        pthread_mutex_unlock(&worker->camera->mutex);
        atomic_store_explicit(&slot->state,REC_RETIRED,memory_order_release);
        output_phase_set(out.stats,OUTPUT_IDLE,0);
    }
    if(error){
        atomic_store(&worker->failed_generation,generation);
        atomic_store(&worker->failed,true);atomic_store(&worker->stop,true);
        statusf(out.stats,worker->output==HUD_STREAMS?
                "Ошибка EGL записи; камеры продолжают работать":
                "Ошибка EGL вывода камеры; остальные выводы продолжают работать");
    }
    /* The producer can still be copying when consumer initialization fails.
     * It acknowledges detachment before any shared resource can be deleted. */
    output_phase_set(out.stats,OUTPUT_DETACH,renderer->diagnostic_generation);
    pthread_mutex_lock(&worker->mutex);
    while(!atomic_load_explicit(&worker->producer_done,memory_order_acquire))
        pthread_cond_wait(&worker->wake,&worker->mutex);
    ANativeWindow *pending=worker->pending;worker->pending=NULL;
    uint64_t closed_generation=worker->generation;
    pthread_mutex_unlock(&worker->mutex);
    if(pending)ANativeWindow_release(pending);
    /* No fence means completion of a producer copy cannot be proven here.
     * Keep one quarantined pool rather than reuse/delete ambiguous GPU memory
     * or accumulate replacement workers. Normal camera outputs remain live. */
    if(atomic_load(&worker->producer_unsafe)){
        statusf(out.stats,"GPU буфер вывода изолирован; требуется перезапуск приложения");return NULL;
    }
    while(!renderer_make_current(renderer,renderer->s,NULL)){
        const struct timespec delay={0,100000000};nanosleep(&delay,NULL);
    }
    output_phase_set(out.stats,OUTPUT_WAIT_SYNC,renderer->diagnostic_generation);
    for(unsigned i=0;i<NBUF;i++)if(worker->slots[i].ready)glWaitSync(worker->slots[i].ready,0,GL_TIMEOUT_IGNORED);
    output_phase_set(out.stats,OUTPUT_FINISH,renderer->diagnostic_generation);
    glFinish();
    for(unsigned i=0;i<NBUF;i++){
        recorder_slot_t *slot=&worker->slots[i];
        if(slot->ready)glDeleteSync(slot->ready);
        if(slot->finished)glDeleteSync(slot->finished);
        if(slot->texture)glDeleteTextures(1,&slot->texture);
    }
    while(recorder_close_window(renderer,&out)){
        const struct timespec delay={0,100000000};nanosleep(&delay,NULL);
    }
    output_phase_set(out.stats,OUTPUT_DETACH,renderer->diagnostic_generation);
    renderer_free(renderer);
    output_phase_set(out.stats,OUTPUT_IDLE,0);
    pthread_mutex_lock(&worker->camera->mutex);
    shared_output_t *desired=&worker->camera->outputs[worker->output];
    desired->preparing=0;
    if(desired->closed_generation<closed_generation)desired->closed_generation=closed_generation;
    /* Later pure cancellations own no replacement Window. They are also closed
     * now; a newer wanted/pending target still requires its own worker ACK. */
    if(!desired->wanted&&!desired->pending&&!desired->window&&
            desired->closed_generation<desired->generation)
        desired->closed_generation=desired->generation;
    if(!desired->wanted||desired->generation==closed_generation)
        atomic_store(&out.stats->running,false);
    atomic_store_explicit(&worker->exited,true,memory_order_release);
    pthread_cond_broadcast(&worker->camera->outputs_changed);
    pthread_mutex_unlock(&worker->camera->mutex);return NULL;
}
static async_recorder_t *async_create_worker(renderer_t *producer,shared_camera_t *camera,int output,uint64_t generation){
    async_recorder_t *worker=calloc(1,sizeof(*worker));if(!worker)return NULL;
    pthread_mutex_init(&worker->mutex,NULL);pthread_cond_init(&worker->wake,NULL);
    worker->input=camera->input;worker->output=output;worker->camera=camera;worker->generation=generation;
    renderer_t *renderer=&worker->renderer;renderer->d=producer->d;renderer->cfg=producer->cfg;
    renderer->diagnostic=camera->outputs[output].stats;renderer->diagnostic_generation=generation;
    output_diagnostics_reset(renderer->diagnostic);
    EGLint context_attrs[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE},pb[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
    renderer->s=eglCreatePbufferSurface(renderer->d,renderer->cfg,pb);
    renderer->c=eglCreateContext(renderer->d,renderer->cfg,producer->c,context_attrs);
    renderer->recordable=output==HUD_STREAMS;renderer->presentation_time=(void *)eglGetProcAddress("eglPresentationTimeANDROID");
    if(renderer->s==EGL_NO_SURFACE||renderer->c==EGL_NO_CONTEXT)goto fail;
    for(unsigned i=0;i<NBUF;i++){
        glGenTextures(1,&worker->slots[i].texture);glBindTexture(GL_TEXTURE_2D,worker->slots[i].texture);
        texture_params();glTexStorage2D(GL_TEXTURE_2D,1,GL_RGBA8,W/2,H);
    }
    if(glGetError()!=GL_NO_ERROR)goto fail;
    if(pthread_create(&worker->thread,NULL,recorder_output_thread,worker))goto fail;
    return worker;
fail:
    for(unsigned i=0;i<NBUF;i++)if(worker->slots[i].texture)glDeleteTextures(1,&worker->slots[i].texture);
    if(renderer->c&&renderer->c!=EGL_NO_CONTEXT)eglDestroyContext(renderer->d,renderer->c);
    if(renderer->s&&renderer->s!=EGL_NO_SURFACE)eglDestroySurface(renderer->d,renderer->s);
    pthread_cond_destroy(&worker->wake);pthread_mutex_destroy(&worker->mutex);free(worker);return NULL;
}
/* Called by the sole capture owner, or under lifecycle_mutex after it joined.
 * Reaping is conditional: no controller waits for an encoder driver's return. */
static void async_reap_completed(shared_camera_t *camera,int output){
    async_recorder_t *worker=async_worker(camera,output);
    if(!worker||!atomic_load_explicit(&worker->exited,memory_order_acquire))return;
    pthread_join(worker->thread,NULL);pthread_cond_destroy(&worker->wake);pthread_mutex_destroy(&worker->mutex);
    free(worker);async_set_worker(camera,output,NULL);
}
/* Capture is the sole owner of worker pointers. The old worker must finish
 * before its slot can be replaced, even across physical capture restarts. */
static void async_apply_output(shared_camera_t *camera,renderer_t *producer,int output){
    async_recorder_t *worker=async_worker(camera,output);
    pthread_mutex_lock(&camera->mutex);shared_output_t *desired=&camera->outputs[output];
    if(worker&&(atomic_load(&worker->producer_unsafe)||
            (atomic_load(&worker->failed)&&desired->generation==atomic_load(&worker->failed_generation)))){
        desired->wanted=0;atomic_store(&desired->stats->running,false);
        if(output<HUD_STREAMS)atomic_fetch_and(&visible_output_mask,~(1ull<<(camera->input*HUD_STREAMS+output)));
    }
    int wanted=desired->wanted;uint64_t generation=desired->generation,requested_ns=desired->requested_ns;
    if(!wanted){
        if(desired->pending)ANativeWindow_release(desired->pending);desired->pending=NULL;
        if(desired->window)ANativeWindow_release(desired->window);desired->window=NULL;
    }
    pthread_mutex_unlock(&camera->mutex);
    if(worker&&atomic_load_explicit(&worker->exited,memory_order_acquire)){
        async_reap_completed(camera,output);worker=NULL;
    }
    if(worker&&(!wanted||atomic_load(&worker->stop))){
        /* Publish the stop generation only while this is still an active worker.
         * A newer desired target belongs to its future replacement. */
        if(!atomic_load(&worker->stop)){
            pthread_mutex_lock(&worker->mutex);
            worker->wanted=0;worker->generation=generation;
            pthread_mutex_unlock(&worker->mutex);
        }
        recorder_detach_producer(worker);return;
    }
    if(!worker&&!wanted){
        pthread_mutex_lock(&camera->mutex);
        if(desired->closed_generation<generation)desired->closed_generation=generation;
        pthread_cond_broadcast(&camera->outputs_changed);pthread_mutex_unlock(&camera->mutex);return;
    }
    if(!worker&&wanted){
        pthread_mutex_lock(&camera->mutex);desired->preparing=1;pthread_mutex_unlock(&camera->mutex);
        worker=async_create_worker(producer,camera,output,generation);async_set_worker(camera,output,worker);
        if(!worker){
            pthread_mutex_lock(&camera->mutex);desired->wanted=0;desired->preparing=0;pthread_mutex_unlock(&camera->mutex);
            statusf(desired->stats,"Не удалось создать независимый EGL вывод камеры");return;
        }
    }
    if(!worker)return;
    pthread_mutex_lock(&camera->mutex);
    int changed=desired->pending!=NULL;
    if(desired->pending){
        if(desired->window)ANativeWindow_release(desired->window);
        desired->window=desired->pending;desired->pending=NULL;
    }
    pthread_mutex_lock(&worker->mutex);
    if(desired->window&&(changed||!worker->wanted||worker->generation!=generation)){
        /* Desired and worker references are independent: replacing a blocked
         * output preserves the next request until its old worker finishes. */
        ANativeWindow_acquire(desired->window);
        if(worker->pending)ANativeWindow_release(worker->pending);worker->pending=desired->window;
    }
    worker->wanted=wanted;worker->generation=generation;worker->requested_ns=requested_ns;
    pthread_cond_signal(&worker->wake);pthread_mutex_unlock(&worker->mutex);pthread_mutex_unlock(&camera->mutex);
}
static void async_detach_all(shared_camera_t *camera){
    for(int output=0;output<SHARED_OUTPUTS;output++){
        async_recorder_t *worker=async_worker(camera,output);
        if(!worker)continue;
        pthread_mutex_lock(&camera->mutex);
        uint64_t generation=camera->outputs[output].generation;
        int wanted=camera->outputs[output].wanted;
        pthread_mutex_unlock(&camera->mutex);
        if(!atomic_load(&worker->stop)){
            pthread_mutex_lock(&worker->mutex);worker->wanted=0;
            if(!wanted)worker->generation=generation;
            pthread_mutex_unlock(&worker->mutex);
        }
        recorder_detach_producer(worker);
    }
}
static recorder_slot_t *recorder_reserve_slot(async_recorder_t *worker){
    for(unsigned pass=0;pass<2;pass++)for(unsigned i=0;i<NBUF;i++){
        recorder_slot_t *slot=&worker->slots[i];int state=atomic_load_explicit(&slot->state,memory_order_acquire);
        if(state==REC_RETIRED){
            GLenum waited=slot->finished?glClientWaitSync(slot->finished,0,0):GL_WAIT_FAILED;
            if(waited==GL_ALREADY_SIGNALED||waited==GL_CONDITION_SATISFIED){
                glDeleteSync(slot->finished);slot->finished=NULL;atomic_store(&slot->state,REC_FREE);state=REC_FREE;
            }else if(waited==GL_WAIT_FAILED){
                atomic_store(&worker->failed_generation,slot->generation);
                atomic_store(&worker->failed,true);atomic_store(&worker->stop,true);return NULL;
            }
        }
        /* Prefer a free slot; replace pending frames only when the consumer is
         * behind. A pending copy is in this same producer context, so replacing
         * it is ordered and does not race a consumer's read. */
        int expected=pass==0?REC_FREE:REC_READY;
        if(state==expected&&atomic_compare_exchange_strong_explicit(&slot->state,&expected,REC_WRITING,
                memory_order_acquire,memory_order_relaxed)){
            if(pass==1)atomic_fetch_add(&worker->camera->outputs[worker->output].stats->replaced,1);
            if(slot->ready){glDeleteSync(slot->ready);slot->ready=NULL;}
            return slot;
        }
    }
    return NULL;
}
static int recorder_reserve_frame(shared_camera_t *camera,int output,uint64_t due_generation,
        recorder_reservation_t *reservation){
    async_recorder_t *worker=async_worker(camera,output);
    if(!worker||atomic_load(&worker->stop))return ADMISSION_CANCELLED;
    pthread_mutex_lock(&camera->mutex);
    shared_output_t *out=&camera->outputs[output];
    int current=!camera->stop&&out->wanted&&out->generation==due_generation;
    uint64_t requested_ns=out->requested_ns;
    pthread_mutex_unlock(&camera->mutex);
    if(!current)return ADMISSION_CANCELLED;
    recorder_slot_t *slot=recorder_reserve_slot(worker);
    if(!slot){atomic_fetch_add(&out->stats->replaced,1);return ADMISSION_NO_CAPACITY;}
    *reservation=(recorder_reservation_t){worker,slot,due_generation,requested_ns,output};
    return ADMISSION_RESERVED;
}
static int recorder_reservation_current(shared_camera_t *camera,const recorder_reservation_t *reservation){
    if(!reservation->slot||atomic_load(&reservation->worker->stop))return 0;
    pthread_mutex_lock(&camera->mutex);
    shared_output_t *out=&camera->outputs[reservation->output];
    int current=!camera->stop&&out->wanted&&out->generation==reservation->generation&&
            async_worker(camera,reservation->output)==reservation->worker;
    pthread_mutex_unlock(&camera->mutex);
    return current;
}
static void recorder_cancel_reservation(shared_camera_t *camera,recorder_reservation_t *reservation){
    if(!reservation->slot)return;
    atomic_store_explicit(&reservation->slot->state,REC_FREE,memory_order_release);
    reservation->slot=NULL;
    atomic_fetch_add(&recorders[camera->input].cancelled_reservations,1);
}
static int recorder_copy_program(renderer_t *producer){
    if(producer->recorder_copy_program)return 0;
    const char *vs="#version 300 es\nconst vec2 p[3]=vec2[3](vec2(-1.,-1.),vec2(3.,-1.),vec2(-1.,3.));void main(){gl_Position=vec4(p[gl_VertexID],0.,1.);}";
    const char *fs="#version 300 es\nprecision highp float;uniform sampler2D image;out vec4 color;void main(){color=texelFetch(image,ivec2(gl_FragCoord.xy),0);}";
    GLuint vert=shader(GL_VERTEX_SHADER,vs),frag=shader(GL_FRAGMENT_SHADER,fs);
    if(!vert||!frag){if(vert)glDeleteShader(vert);if(frag)glDeleteShader(frag);return -1;}
    GLuint program=glCreateProgram();glAttachShader(program,vert);glAttachShader(program,frag);
    glLinkProgram(program);glDeleteShader(vert);glDeleteShader(frag);
    GLint linked=0;glGetProgramiv(program,GL_LINK_STATUS,&linked);
    if(!linked){glDeleteProgram(program);return -1;}
    producer->recorder_copy_program=program;producer->recorder_copy_sampler=glGetUniformLocation(program,"image");return 0;
}
static void async_offer_reserved(shared_camera_t *camera,renderer_t *producer,
        recorder_reservation_t *reservation,unsigned source,uint64_t timestamp,
        uint64_t get_ns,const draw_metrics_t *upload){
    if(!recorder_reservation_current(camera,reservation)){
        recorder_cancel_reservation(camera,reservation);return;
    }
    async_recorder_t *worker=reservation->worker;
    recorder_slot_t *slot=reservation->slot;
    uint64_t copy_start=now_ns(),copy_cpu_start=thread_cpu_ns();
    if(!producer->recorder_fbo)glGenFramebuffers(1,&producer->recorder_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER,producer->recorder_fbo);
    GLuint source_texture=producer->upload_mode==UPLOAD_DMABUF_RGBA?producer->imported_texture[source]:producer->texture;
    /* DMA-BUF imports need sampling support but need not be renderable FBO
     * attachments. Render a byte-preserving copy into our owned RGBA8 texture. */
    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,slot->texture,0);
    int error=glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE||recorder_copy_program(producer);
    int copied=0;
    if(!error){
        copied=1;
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,source_texture);
        glUseProgram(producer->recorder_copy_program);glUniform1i(producer->recorder_copy_sampler,0);
        glViewport(0,0,W/2,H);glDrawArrays(GL_TRIANGLES,0,3);
        slot->ready=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);glFlush();
        error=!slot->ready;
    }
    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,0,0);
    glBindFramebuffer(GL_FRAMEBUFFER,0);glBindTexture(GL_TEXTURE_2D,source_texture);
    if(glGetError()!=GL_NO_ERROR)error=1;
    uint64_t copy_cpu_end=thread_cpu_ns(),copy_end=now_ns();
    if(copied){
        state_t *stats=worker->camera->outputs[worker->output].stats;
        atomic_fetch_add(&stats->private_copy_total_ns,copy_end-copy_start);
        if(copy_cpu_start&&copy_cpu_end>=copy_cpu_start)
            atomic_fetch_add(&stats->private_copy_cpu_total_ns,copy_cpu_end-copy_cpu_start);
        atomic_fetch_add(&stats->private_copy_count,1);
    }
    if(error){
        /* No speculative FREE on an ambiguous GPU error. The isolated worker
         * waits for producer detachment and finishes before deleting the pool. */
        if(copied&&!slot->ready)atomic_store(&worker->producer_unsafe,true);
        atomic_store(&worker->failed_generation,reservation->generation);
        atomic_store(&worker->failed,true);atomic_store(&worker->stop,true);return;
    }
    slot->timestamp=timestamp;slot->generation=reservation->generation;slot->requested_ns=reservation->requested_ns;
    slot->get_ns=get_ns;slot->upload=*upload;slot->upload_mode=producer->upload_mode;
    atomic_store_explicit(&slot->state,REC_READY,memory_order_release);
    reservation->slot=NULL;
    atomic_fetch_add(&recorders[camera->input].accepted_offers,1);
    recorder_wake(worker);
}
