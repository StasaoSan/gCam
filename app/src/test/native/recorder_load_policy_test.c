#include <assert.h>
#include "../../main/cpp/recorder_load_policy.h"
int main(void) {
 uint64_t s=0,h=0,n=1000000000ull;
 assert(recorder_fps_budget(15,0,n,s,h)==15);
 assert(recorder_fps_budget(25,1,n,s,h)==8);
 s=recorder_pressure_deadline(n,65000000,60000000,s);
 assert(recorder_fps_budget(25,0,n+9000000000ull,s,h)==8);
 h=recorder_pressure_deadline(n,150000000,120000000,h);
 assert(recorder_fps_budget(25,1,n+9000000000ull,s,h)==4);
 assert(recorder_fps_budget(25,0,n+10000000000ull,s,h)==25);
 assert(recorder_fps_budget(25,1,n+10000000000ull,s,h)==8);
 assert(recorder_pressure_deadline(n+1,1000,60000000,s)==s);
 assert(recorder_fps_budget(2,1,n,s,h)==2);
 for(int i=0;i<100000;i++) {
  n+=33000000; s=recorder_pressure_deadline(n,i%90==0?70000000:1000000,60000000,s);
  h=recorder_pressure_deadline(n,i%300==0?150000000:1000000,120000000,h);
  int v=recorder_fps_budget(15,i%800<20,n,s,h);assert(v>0&&v<=15);
 }
 return 0;
}
