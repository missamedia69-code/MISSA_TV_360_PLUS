/* Minimal JSON bridge: the browser preview runs the exact same C99 game logic.
 * One game per loaded shared library; the HTTP server serializes calls.
 */
#include "../bomber.h"
#include <stdio.h>
#include <string.h>

static BMGame game;
static char json[16000];

void web_new(unsigned seed, unsigned players) {
    bm_new(&game, seed ? seed : 1, (uint8_t)(players == 2));
}
void web_tick(unsigned p1, unsigned p2, unsigned frames) {
    unsigned i;
    if (frames > 8) frames = 8;
    for (i = 0; i < frames; ++i) bm_tick(&game, (uint8_t)p1, (uint8_t)p2);
}
const char *web_state(void) {
    size_t at = 0;
    unsigned x, y, i;
#define ADD(...) do { \
    int n = snprintf(json + at, sizeof json - at, __VA_ARGS__); \
    if (n < 0 || (size_t)n >= sizeof json - at) return "{}"; \
    at += (size_t)n; \
} while (0)
    ADD("{\"stage\":%u,\"state\":%u,\"score\":%lu,\"frame\":%lu,\"players\":[",
        game.stage, game.state, (unsigned long)game.score, (unsigned long)game.frame);
    for (i = 0; i < 2; ++i) {
        BMPlayer *p = &game.players[i];
        ADD("%s{\"x\":%u,\"y\":%u,\"lives\":%u,\"enabled\":%u,\"range\":%u,\"bombs\":%u,\"invulnerable\":%u}",
            i ? "," : "", p->x, p->y, p->lives, p->enabled, p->range, p->bombs, p->invulnerable);
    }
    ADD("],\"enemies\":[");
    for (i = 0; i < BM_MAX_ENEMIES; ++i)
        ADD("%s[%u,%u,%u]", i ? "," : "", game.enemies[i].x, game.enemies[i].y, game.enemies[i].active);
    ADD("],\"bombs\":[");
    for (i = 0; i < BM_MAX_BOMBS; ++i)
        ADD("%s[%u,%u,%u]", i ? "," : "", game.bombs[i].x, game.bombs[i].y, game.bombs[i].active);
    ADD("],\"tiles\":[");
    for (y = 0; y < BM_H; ++y) {
        ADD("%s[", y ? "," : "");
        for (x = 0; x < BM_W; ++x) ADD("%s%u", x ? "," : "", game.tiles[y][x]);
        ADD("]");
    }
    ADD("],\"fire\":[");
    for (y = 0; y < BM_H; ++y) {
        ADD("%s[", y ? "," : "");
        for (x = 0; x < BM_W; ++x) ADD("%s%u", x ? "," : "", game.fire[y][x]);
        ADD("]");
    }
    ADD("],\"upgrades\":[");
    for (y = 0; y < BM_H; ++y) {
        ADD("%s[", y ? "," : "");
        for (x = 0; x < BM_W; ++x) ADD("%s%u", x ? "," : "", game.upgrades[y][x]);
        ADD("]");
    }
    ADD("]}");
#undef ADD
    return json;
}
