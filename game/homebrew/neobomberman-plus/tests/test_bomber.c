#include "../bomber.h"
#include <assert.h>
#include <stdio.h>

static BMGame g;

static void test_board(void) {
    bm_new(&g, 123, 0);
    assert(g.tiles[0][1]==BM_STONE);
    assert(g.tiles[2][2]==BM_STONE);
    assert(g.tiles[1][1]==BM_EMPTY);
    assert(g.tiles[1][2]==BM_EMPTY);
    assert(g.enemy_count==3 && bm_enemies_left(&g)==3);
    assert(g.players[0].lives==3 && !g.players[1].enabled);
    bm_new(&g, 123, 1);
    assert(g.players[1].enabled && g.tiles[1][BM_W-2]==BM_EMPTY);
}

static void test_movement_and_bonus(void) {
    bm_new(&g, 123, 0);
    bm_tick(&g, BM_LEFT,0);
    assert(g.players[0].x==1); /* bordure infranchissable */
    g.upgrades[1][2]=BM_FIRE_UP;
    bm_tick(&g, BM_RIGHT,0);
    assert(g.players[0].x==2 && g.players[0].range==3);
    assert(!g.upgrades[1][2] && g.score==50);
    bm_tick(&g, BM_RIGHT,0);
    assert(g.players[0].x==2); /* temporisation des déplacements */
}

static void test_bomb_limit_and_edge(void) {
    bm_new(&g, 123, 0);
    bm_tick(&g, BM_A,0);
    assert(bm_bomb_at(&g,1,1));
    assert(!bm_place_bomb(&g,0)); /* capacité initiale : une bombe */
    bm_tick(&g,BM_A,0);           /* maintenir A n'en pose pas d'autre */
    assert(g.bombs[1].active==0);
    g.players[0].bombs=2;
    g.players[0].x=2;
    bm_tick(&g,0,0);
    bm_tick(&g,BM_A,0);
    assert(bm_bomb_at(&g,2,1));
}

static void test_explosion_blocking_and_chain(void) {
    uint8_t i;
    bm_new(&g,123,0);
    /* Éviter que l'IA ou le changement de manche n'affecte la mesure. */
    g.tiles[1][2]=BM_BRICK;
    g.tiles[1][3]=BM_BRICK;
    g.players[0].invulnerable=200;
    assert(bm_place_bomb(&g,0));
    for (i=0; i<BM_FUSE-1; ++i) bm_tick(&g,0,0);
    assert(g.bombs[0].active);
    bm_tick(&g,0,0);
    assert(!g.bombs[0].active && g.fire[1][1]);
    assert(g.tiles[1][2]==BM_EMPTY);
    assert(g.tiles[1][3]==BM_BRICK); /* le feu s'arrête sur une brique */
    assert(!g.fire[2][2]); /* un pilier résiste à la bombe */

    bm_new(&g,321,0);
    g.players[0].bombs=2;
    assert(bm_place_bomb(&g,0));
    g.players[0].x=1;g.players[0].y=2;
    assert(bm_place_bomb(&g,0));
    g.bombs[0].fuse=1;g.bombs[1].fuse=80;
    bm_tick(&g,0,0);
    assert(!g.bombs[0].active && !g.bombs[1].active);
    assert(g.fire[2][1]); /* réaction en chaîne immédiate */
}

static void test_damage_and_next_stage(void) {
    uint16_t i;
    bm_new(&g,456,0);
    g.players[0].invulnerable=0;
    g.fire[1][1]=3;
    bm_tick(&g,0,0);
    assert(g.players[0].lives==2 && g.players[0].invulnerable>0);
    bm_tick(&g,0,0);
    assert(g.players[0].lives==2);
    for (i=0; i<BM_MAX_ENEMIES; ++i) g.enemies[i].active=0;
    bm_tick(&g,0,0);
    assert(g.state==BM_ROUND_CLEAR && g.round_delay==120);
    for (i=0; i<120; ++i) bm_tick(&g,0,0);
    assert(g.stage==2 && g.state==BM_PLAYING && g.players[0].lives==2);
}

static void test_game_over(void) {
    bm_new(&g,456,0);
    g.players[0].lives=1;
    g.players[0].invulnerable=0;
    g.fire[1][1]=2;
    bm_tick(&g,0,0);
    assert(g.state==BM_GAME_OVER && !g.players[0].lives);
    bm_tick(&g,0,0);
    assert(g.state==BM_GAME_OVER);
}

static void test_long_sessions(void) {
    unsigned seed, step;
    for (seed=1; seed<=12; ++seed) {
        bm_new(&g,seed,(uint8_t)(seed&1));
        for (step=0; step<2000; ++step) {
            static const uint8_t moves[]={BM_UP,BM_DOWN,BM_LEFT,BM_RIGHT,BM_A,0};
            uint8_t x,y,i;
            bm_tick(&g,moves[(step+seed)%6],moves[(step+seed*3)%6]);
            for (y=0; y<BM_H; ++y) for (x=0; x<BM_W; ++x)
                if (!x || !y || x==BM_W-1 || y==BM_H-1)
                    assert(g.tiles[y][x]==BM_STONE);
            for (i=0; i<2; ++i) if (g.players[i].enabled) {
                assert(g.players[i].x>0 && g.players[i].x<BM_W-1);
                assert(g.players[i].y>0 && g.players[i].y<BM_H-1);
            }
            if (g.state==BM_GAME_OVER) bm_new(&g,seed+step,(uint8_t)(seed&1));
        }
    }
}

int main(void) {
    test_board();
    test_movement_and_bonus();
    test_bomb_limit_and_edge();
    test_explosion_blocking_and_chain();
    test_damage_and_next_stage();
    test_game_over();
    test_long_sessions();
    puts("Bomber Plus : 7 tests OK");
    return 0;
}
