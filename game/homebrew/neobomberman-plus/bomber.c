#include "bomber.h"
#include <string.h>

static uint32_t roll(BMGame *g) {
    uint32_t x = g->random;
    x ^= x << 13; x ^= x >> 17; x ^= x << 5;
    return g->random = x ? x : 1;
}

static uint8_t clear_corner(uint8_t x, uint8_t y) {
    return ((x <= 2 || x >= BM_W-3) && (y <= 2 || y >= BM_H-3));
}

static void stage_init(BMGame *g) {
    static const uint8_t starts[BM_MAX_ENEMIES][2] = {
        {BM_W-2, BM_H-2}, {1, BM_H-2}, {BM_W-2, BM_H-4},
        {1, BM_H-4}, {BM_W-4, BM_H-2}, {3, BM_H-2},
        {BM_W-4, BM_H-4}, {3, BM_H-4}
    };
    uint8_t x, y, i;
    memset(g->tiles, 0, sizeof g->tiles);
    memset(g->fire, 0, sizeof g->fire);
    memset(g->upgrades, 0, sizeof g->upgrades);
    memset(g->bombs, 0, sizeof g->bombs);
    memset(g->enemies, 0, sizeof g->enemies);
    for (y=0; y<BM_H; ++y) for (x=0; x<BM_W; ++x) {
        if (!x || !y || x==BM_W-1 || y==BM_H-1 || (!(x&1) && !(y&1)))
            g->tiles[y][x] = BM_STONE;
        else if (!clear_corner(x,y) && (roll(g)%100) < 39)
            g->tiles[y][x] = BM_BRICK;
    }
    g->players[0].x=1; g->players[0].y=1;
    g->players[1].x=BM_W-2; g->players[1].y=1;
    for (i=0; i<2; ++i) {
        g->players[i].cooldown=0;
        g->players[i].invulnerable=90;
        g->players[i].previous_input=0;
    }
    g->enemy_count = (uint8_t)(2 + g->stage);
    if (g->enemy_count > BM_MAX_ENEMIES) g->enemy_count=BM_MAX_ENEMIES;
    for (i=0; i<g->enemy_count; ++i) {
        x=starts[i][0]; y=starts[i][1];
        if (g->tiles[y][x]==BM_STONE) { x=BM_W-2; y=BM_H-2; }
        g->tiles[y][x]=BM_EMPTY;
        g->enemies[i].x=x; g->enemies[i].y=y; g->enemies[i].active=1;
    }
    g->state=BM_PLAYING;
    g->round_delay=0;
}

void bm_new(BMGame *g, uint32_t seed, uint8_t two_player) {
    uint8_t i;
    memset(g, 0, sizeof *g);
    g->random=seed ? seed : 1;
    g->stage=1;
    g->two_player=(uint8_t)!!two_player;
    for (i=0; i<2; ++i) {
        g->players[i].enabled=(uint8_t)(i==0 || two_player);
        g->players[i].lives=3;
        g->players[i].bombs=1;
        g->players[i].range=2;
    }
    stage_init(g);
}

uint8_t bm_bomb_at(const BMGame *g, uint8_t x, uint8_t y) {
    uint8_t i;
    for (i=0; i<BM_MAX_BOMBS; ++i)
        if (g->bombs[i].active && g->bombs[i].x==x && g->bombs[i].y==y) return 1;
    return 0;
}

uint8_t bm_enemies_left(const BMGame *g) {
    uint8_t i, n=0;
    for (i=0; i<BM_MAX_ENEMIES; ++i) n+=(uint8_t)!!g->enemies[i].active;
    return n;
}

uint8_t bm_place_bomb(BMGame *g, uint8_t player_index) {
    uint8_t i, active=0;
    BMPlayer *p;
    if (player_index>1 || g->state!=BM_PLAYING) return 0;
    p=&g->players[player_index];
    if (!p->enabled || !p->lives || bm_bomb_at(g,p->x,p->y)) return 0;
    for (i=0; i<BM_MAX_BOMBS; ++i)
        if (g->bombs[i].active && g->bombs[i].owner==player_index) ++active;
    if (active>=p->bombs) return 0;
    for (i=0; i<BM_MAX_BOMBS; ++i) if (!g->bombs[i].active) {
        g->bombs[i].x=p->x; g->bombs[i].y=p->y;
        g->bombs[i].owner=player_index; g->bombs[i].range=p->range;
        g->bombs[i].fuse=BM_FUSE; g->bombs[i].active=1;
        return 1;
    }
    return 0;
}

static void hit_fire(BMGame *g, uint8_t x, uint8_t y) {
    uint8_t i;
    g->fire[y][x]=BM_FIRE_TIME;
    for (i=0; i<BM_MAX_BOMBS; ++i)
        if (g->bombs[i].active && g->bombs[i].x==x && g->bombs[i].y==y)
            g->bombs[i].fuse=0; /* réaction en chaîne dans la même frame */
}

static void explode(BMGame *g, BMBomb *b) {
    static const int8_t dx[4]={1,-1,0,0}, dy[4]={0,0,1,-1};
    uint8_t d, step;
    uint8_t x=b->x, y=b->y, range=b->range;
    b->active=0;
    hit_fire(g,x,y);
    for (d=0; d<4; ++d) for (step=1; step<=range; ++step) {
        int16_t nx=(int16_t)x + dx[d]*step, ny=(int16_t)y + dy[d]*step;
        uint8_t cell;
        if (nx<0 || nx>=BM_W || ny<0 || ny>=BM_H) break;
        cell=g->tiles[ny][nx];
        if (cell==BM_STONE) break;
        hit_fire(g,(uint8_t)nx,(uint8_t)ny);
        if (cell==BM_BRICK) {
            g->tiles[ny][nx]=BM_EMPTY;
            g->score+=10;
            if (roll(g)%5==0)
                g->upgrades[ny][nx]=(uint8_t)(1+roll(g)%2);
            break; /* la flamme s'arrête sur la première brique */
        }
    }
}

static void player_step(BMGame *g, uint8_t id, uint8_t input) {
    BMPlayer *p=&g->players[id];
    int8_t dx=0, dy=0;
    int16_t x,y;
    if (!p->enabled || !p->lives) return;
    if (p->cooldown) --p->cooldown;
    if (p->invulnerable) --p->invulnerable;
    if (!p->cooldown) {
        if (input&BM_UP) dy=-1;
        else if (input&BM_DOWN) dy=1;
        else if (input&BM_LEFT) dx=-1;
        else if (input&BM_RIGHT) dx=1;
        x=(int16_t)p->x+dx; y=(int16_t)p->y+dy;
        if ((dx || dy) && x>0 && x<BM_W-1 && y>0 && y<BM_H-1 &&
            g->tiles[y][x]==BM_EMPTY && !bm_bomb_at(g,(uint8_t)x,(uint8_t)y)) {
            p->x=(uint8_t)x; p->y=(uint8_t)y;
            p->cooldown=7;
        }
    }
    if ((input&BM_A) && !(p->previous_input&BM_A)) bm_place_bomb(g,id);
    p->previous_input=input;
    if (g->upgrades[p->y][p->x]) {
        if (g->upgrades[p->y][p->x]==BM_FIRE_UP && p->range<5) ++p->range;
        if (g->upgrades[p->y][p->x]==BM_BOMB_UP && p->bombs<4) ++p->bombs;
        g->upgrades[p->y][p->x]=BM_NONE;
        g->score+=50;
    }
}

static void enemies_step(BMGame *g) {
    static const int8_t dx[5]={1,-1,0,0,0},dy[5]={0,0,1,-1,0};
    uint8_t i, dir;
    if (g->frame%15) return;
    for (i=0; i<BM_MAX_ENEMIES; ++i) if (g->enemies[i].active) {
        int16_t nx,ny;
        dir=(uint8_t)(roll(g)%5);
        nx=(int16_t)g->enemies[i].x+dx[dir];
        ny=(int16_t)g->enemies[i].y+dy[dir];
        if (nx>0 && nx<BM_W-1 && ny>0 && ny<BM_H-1 &&
            g->tiles[ny][nx]==BM_EMPTY && !g->fire[ny][nx] &&
            !bm_bomb_at(g,(uint8_t)nx,(uint8_t)ny)) {
            g->enemies[i].x=(uint8_t)nx; g->enemies[i].y=(uint8_t)ny;
        }
    }
}

static void apply_damage(BMGame *g) {
    uint8_t i, j;
    for (i=0; i<BM_MAX_ENEMIES; ++i) if (g->enemies[i].active &&
        g->fire[g->enemies[i].y][g->enemies[i].x]) {
        g->enemies[i].active=0; g->score+=100;
    }
    for (j=0; j<2; ++j) {
        BMPlayer *p=&g->players[j];
        uint8_t hit=g->fire[p->y][p->x]!=0;
        if (!p->enabled || !p->lives) continue;
        for (i=0; i<BM_MAX_ENEMIES; ++i)
            if (g->enemies[i].active && g->enemies[i].x==p->x &&
                g->enemies[i].y==p->y) hit=1;
        if (hit && !p->invulnerable) {
            --p->lives;
            if (p->lives) {
                p->x=(uint8_t)(j ? BM_W-2 : 1); p->y=1;
                p->invulnerable=90; p->cooldown=0;
            }
        }
    }
    if ((!g->players[0].enabled || !g->players[0].lives) &&
        (!g->players[1].enabled || !g->players[1].lives)) g->state=BM_GAME_OVER;
    else if (!bm_enemies_left(g)) {g->state=BM_ROUND_CLEAR; g->round_delay=120;}
}

void bm_tick(BMGame *g, uint8_t p1_input, uint8_t p2_input) {
    uint8_t i, x, y, detonated;
    ++g->frame;
    if (g->state==BM_GAME_OVER) return;
    if (g->state==BM_ROUND_CLEAR) {
        if (g->round_delay && !--g->round_delay) {
            if (g->stage<255) ++g->stage;
            stage_init(g);
        }
        return;
    }
    for (y=0; y<BM_H; ++y) for (x=0; x<BM_W; ++x)
        if (g->fire[y][x]) --g->fire[y][x];
    player_step(g,0,p1_input);
    player_step(g,1,p2_input);
    enemies_step(g);
    for (i=0; i<BM_MAX_BOMBS; ++i)
        if (g->bombs[i].active && g->bombs[i].fuse>0) --g->bombs[i].fuse;
    do {
        detonated=0;
        for (i=0; i<BM_MAX_BOMBS; ++i)
            if (g->bombs[i].active && g->bombs[i].fuse==0) {
                explode(g,&g->bombs[i]); detonated=1;
            }
    } while (detonated);
    apply_damage(g);
}
