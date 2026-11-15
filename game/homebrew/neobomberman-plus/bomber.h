/* Prototype de jeu de labyrinthe Neo Geo. Code original, sans ROM du jeu commercial. */
#ifndef BOMBER_H
#define BOMBER_H

#include <stdint.h>

#define BM_W 13
#define BM_H 11
#define BM_MAX_BOMBS 16
#define BM_MAX_ENEMIES 8
#define BM_FUSE 90      /* 1,5 s à 60 Hz */
#define BM_FIRE_TIME 24 /* 0,4 s à 60 Hz */

enum { BM_EMPTY, BM_STONE, BM_BRICK };
enum { BM_NONE, BM_FIRE_UP, BM_BOMB_UP };
enum { BM_UP=1, BM_DOWN=2, BM_LEFT=4, BM_RIGHT=8, BM_A=16 };
enum { BM_PLAYING, BM_ROUND_CLEAR, BM_GAME_OVER };

typedef struct {
    uint8_t x, y, lives, enabled, bombs, range, cooldown, invulnerable;
    uint8_t previous_input;
} BMPlayer;

typedef struct { uint8_t x, y, active; } BMEnemy;
typedef struct {
    uint8_t x, y, owner, range, active;
    int16_t fuse;
} BMBomb;

typedef struct {
    uint8_t tiles[BM_H][BM_W];
    uint8_t fire[BM_H][BM_W];
    uint8_t upgrades[BM_H][BM_W];
    BMPlayer players[2];
    BMEnemy enemies[BM_MAX_ENEMIES];
    BMBomb bombs[BM_MAX_BOMBS];
    uint32_t random, score, frame;
    uint16_t round_delay;
    uint8_t stage, state, enemy_count, two_player;
} BMGame;

void bm_new(BMGame *g, uint32_t seed, uint8_t two_player);
void bm_tick(BMGame *g, uint8_t p1_input, uint8_t p2_input);
uint8_t bm_place_bomb(BMGame *g, uint8_t player_index);
uint8_t bm_bomb_at(const BMGame *g, uint8_t x, uint8_t y);
uint8_t bm_enemies_left(const BMGame *g);

#endif
