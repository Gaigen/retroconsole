/* Synthetic libretro ABI fixture, not an emulator or game. No ROM/BIOS required. */
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include <string.h>
typedef bool (*environment_t)(unsigned,void*);
typedef void (*video_t)(const void*,unsigned,unsigned,size_t);
typedef size_t (*audio_t)(const int16_t*,size_t);
typedef void (*poll_t)(void);
typedef int16_t (*input_t)(unsigned,unsigned,unsigned,unsigned);
static environment_t environment;
static video_t video;
static audio_t audio;
static poll_t poll;
static input_t input;
static uint32_t ticks;
static uint8_t ram[16];
struct system_info {const char *name,*version,*extensions;bool fullpath,block_extract;};
struct geometry {unsigned width,height,max_width,max_height;float aspect;};
struct av_info {struct geometry geometry;double fps,rate;};
unsigned retro_api_version(void){return 1;}
void retro_set_environment(environment_t cb){environment=cb;}
void retro_set_video_refresh(video_t cb){video=cb;}
void retro_set_audio_sample(void *cb){(void)cb;}
void retro_set_audio_sample_batch(audio_t cb){audio=cb;}
void retro_set_input_poll(poll_t cb){poll=cb;}
void retro_set_input_state(input_t cb){input=cb;}
void retro_init(void){int format=1;environment(10,&format);}
void retro_deinit(void){}
void retro_get_system_info(struct system_info *info){memset(info,0,sizeof(*info));info->name="BackportFixture";info->version="1";info->extensions="bin";}
void retro_get_system_av_info(struct av_info *info){memset(info,0,sizeof(*info));info->geometry=(struct geometry){2,2,2,2,1.0f};info->fps=60;info->rate=48000;}
bool retro_load_game(const void *game){(void)game;ticks=0;memset(ram,0,sizeof(ram));return true;}
void retro_unload_game(void){}
void retro_set_controller_port_device(unsigned port,unsigned device){(void)port;(void)device;}
void retro_run(void){
 uint32_t pixels[4]={0x00112233,0x00aabbcc,0x00000000,0x00ffffff};
 int16_t samples[32];for(int i=0;i<32;i++)samples[i]=(int16_t)(1000+i);
 poll();ram[0]=(uint8_t)(input(0,1,0,0)!=0);ticks++;
 video(pixels,2,2,8);audio(samples,16);
}
void retro_reset(void){ticks=0;}
size_t retro_serialize_size(void){return sizeof(ticks);}
bool retro_serialize(void *data,size_t size){if(size!=sizeof(ticks))return false;memcpy(data,&ticks,size);return true;}
bool retro_unserialize(const void *data,size_t size){if(size!=sizeof(ticks))return false;memcpy(&ticks,data,size);return true;}
void *retro_get_memory_data(unsigned id){return id==0?ram:NULL;}
size_t retro_get_memory_size(unsigned id){return id==0?sizeof(ram):0;}
unsigned retro_get_region(void){return 0;}
void retro_cheat_reset(void){}
void retro_cheat_set(unsigned index,bool enabled,const char*code){(void)index;(void)enabled;(void)code;}
