/* Adaptateur ngdevkit pour Neo Geo AES/MVS : prototype visuel sur couche FIX.
 * Il ne réutilise ni le code ni la ROM commerciale Neo Bomberman.
 */
#include <ngdevkit/neogeo.h>
#include <ngdevkit/ng-fix.h>
#include <ngdevkit/ng-video.h>
#include <stdio.h>
#include "bomber.h"

static BMGame game;

static void init_video(void) {
    static const u16 ink[8] = {
        0x0fff, /* blanc : HUD */
        0x0555, /* roche grise */
        0x0aa4, /* briques */
        0x00ff, /* P1 jaune */
        0x0f0f, /* P2 violet */
        0x0f50, /* feu */
        0x00f0, /* bonus vert */
        0x0f00  /* ennemis rouges */
    };
    u8 i;
    ng_cls();
    for (i=0; i<8; ++i) {
        MMAP_PALBANK1[i*16]=0x8000;
        MMAP_PALBANK1[i*16+1]=ink[i];
        MMAP_PALBANK1[i*16+2]=0x0222;
    }
    *((volatile u16*)0x401ffe)=0x0011;
}

static uint8_t controls(u8 raw) {
    uint8_t out=0;
    if (raw&CNT_UP) out|=BM_UP;
    if (raw&CNT_DOWN) out|=BM_DOWN;
    if (raw&CNT_LEFT) out|=BM_LEFT;
    if (raw&CNT_RIGHT) out|=BM_RIGHT;
    if (raw&CNT_A) out|=BM_A;
    return out;
}

static void draw_cell(u8 x, u8 y) {
    const char *text="  ";
    u8 pal=0, i;
    if (game.tiles[y][x]==BM_STONE) {text="[]";pal=1;}
    else if (game.tiles[y][x]==BM_BRICK) {text="##";pal=2;}
    else if (game.upgrades[y][x]) {text="++";pal=6;}
    if (bm_bomb_at(&game,x,y)) {text="OO";pal=3;}
    if (game.fire[y][x]) {text="XX";pal=5;}
    for (i=0; i<BM_MAX_ENEMIES; ++i)
        if (game.enemies[i].active && game.enemies[i].x==x && game.enemies[i].y==y) {
            text="EE";pal=7;break;
        }
    for (i=0; i<2; ++i)
        if (game.players[i].enabled && game.players[i].lives &&
            game.players[i].x==x && game.players[i].y==y &&
            (!game.players[i].invulnerable || ((game.frame>>3)&1))) {
            text=i ? "P2":"P1";pal=i ? 4:3;
        }
    ng_text_tall((u8)(7+2*x),(u8)(4+2*y),pal,text);
}

static void draw_board(void) {
    u8 x,y;
    char hud[39];
    snprintf(hud,sizeof hud,"ST %02u  P1 %u  P2 %u  E %u   ",
             (unsigned)game.stage,(unsigned)game.players[0].lives,
             (unsigned)(game.players[1].enabled ? game.players[1].lives : 0),
             (unsigned)bm_enemies_left(&game));
    ng_text(5,2,0,hud);
    for (y=0; y<BM_H; ++y) for (x=0; x<BM_W; ++x) draw_cell(x,y);
    if (game.state==BM_ROUND_CLEAR)
        ng_center_text(28,6,"STAGE CLEAR          ");
    else if (game.state==BM_GAME_OVER)
        ng_center_text(28,7,"GAME OVER - RESET   ");
    else ng_center_text(28,0,"A: BOMB   MOVE: JOYSTICK");
    snprintf(hud,sizeof hud,"SCORE %08lu  RANGE %u  BOMBS %u   ",
             (unsigned long)game.score,(unsigned)game.players[0].range,
             (unsigned)game.players[0].bombs);
    ng_text(3,30,0,hud);
}

int main(void) {
    u8 coop=0;
    init_video();
    ng_center_text_tall(8,3,"BOMBER PLUS");
    ng_center_text(13,0,"NATIVE NEO GEO PROTOTYPE");
    ng_center_text(17,0,"A: SOLO    B: TWO PLAYERS");
    ng_center_text(20,0,"MOVE: JOYSTICK   A: BOMB");
    while (!(bios_p1change & (CNT_A|CNT_B))) ng_wait_vblank();
    if (bios_p1change & CNT_B) coop=1;
    /* La première touche A sert au menu, pas à poser une bombe au départ. */
    while (bios_p1current & (CNT_A|CNT_B)) ng_wait_vblank();
    bm_new(&game,0x4e454f47u,coop);
    ng_cls();
    ng_center_text(0,3,"BOMBER PLUS - NEO GEO");
    for (;;) {
        ng_wait_vblank();
        bm_tick(&game,controls(bios_p1current),controls(bios_p2current));
        if (!(game.frame%4)) draw_board();
        if (game.state==BM_GAME_OVER && (bios_p1change & CNT_B)) {
            bm_new(&game,game.random,coop);
            ng_cls();
        }
    }
    return 0;
}
