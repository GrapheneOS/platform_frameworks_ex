/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma version(1)
#pragma rs java_package_name(com.android.ex.carousel);
#pragma rs set_reflect_license()

#include "rs_graphics.rsh"

typedef struct __attribute__((aligned(4))) Card {
    // *** Update copyCard if you add/remove fields here.
    rs_allocation texture; // basic card texture
    rs_allocation detailTexture; // screen-aligned detail texture
    float2 detailTextureOffset; // offset to add, in screen coordinates
    float2 detailLineOffset; // offset to add to detail line, in screen coordinates
    rs_mesh geometry;
    rs_matrix4x4 matrix; // custom transform for this card/geometry
    int textureState;  // whether or not the primary card texture is loaded.
    int detailTextureState; // whether or not the detail for the card is loaded.
    int geometryState; // whether or not geometry is loaded
    int visible; // not bool because of packing bug?
    int64_t textureTimeStamp; // time when this texture was last updated, in seconds
    int64_t detailTextureTimeStamp; // time when this texture was last updated, in seconds
} Card_t;

typedef struct Ray_s {
    float3 position;
    float3 direction;
} Ray;

typedef struct Plane_s {
    float3 point;
    float3 normal;
    float constant;
} Plane;

typedef struct Cylinder_s {
    float3 center; // center of a y-axis-aligned infinite cylinder
    float radius;
} Cylinder;

typedef struct PerspectiveCamera_s {
    float3 from;
    float3 at;
    float3 up;
    float  fov;
    float  aspect;
    float  near;
    float  far;
} PerspectiveCamera;

typedef struct FragmentShaderConstants_s {
    float fadeAmount;
} FragmentShaderConstants;

// Request states. Used for loading 3D object properties from the Java client.
// Typical properties: texture, geometry and matrices.
enum {
    STATE_INVALID = 0, // item hasn't been loaded
    STATE_LOADING, // we've requested an item but are waiting for it to load
    STATE_LOADED // item was delivered
};

// Client messages *** THIS LIST MUST MATCH THOSE IN CarouselRS.java. ***
static const int CMD_CARD_SELECTED = 100;
static const int CMD_CARD_LONGPRESS = 110;
static const int CMD_REQUEST_TEXTURE = 200;
static const int CMD_INVALIDATE_TEXTURE = 210;
static const int CMD_REQUEST_GEOMETRY = 300;
static const int CMD_INVALIDATE_GEOMETRY = 310;
static const int CMD_ANIMATION_STARTED = 400;
static const int CMD_ANIMATION_FINISHED = 500;
static const int CMD_REQUEST_DETAIL_TEXTURE = 600;
static const int CMD_INVALIDATE_DETAIL_TEXTURE = 610;
static const int CMD_PING = 1000;

// Constants
static const int ANIMATION_SCALE_TIME = 200; // Time it takes to animate selected card, in ms
static const float3 SELECTED_SCALE_FACTOR = { 0.2f, 0.2f, 0.2f }; // increase by this %
static const float OVERSCROLL_SLOTS = 1.0f; // amount of allowed overscroll (in slots)

// Debug flags
const bool debugCamera = false; // dumps ray/camera coordinate stuff
const bool debugSelection = false; // logs selection events
const bool debugTextureLoading = false; // for debugging texture load/unload
const bool debugGeometryLoading = false; // for debugging geometry load/unload
const bool debugDetails = false; // for debugging detail texture geometry
const bool debugRendering = false; // flashes display when the frame changes
const bool debugRays = false; // shows visual depiction of hit tests, See renderWithRays().

// Exported variables. These will be reflected to Java set_* variables.
Card_t *cards; // array of cards to draw
// TODO: remove tmpCards code when allocations support resizing
Card_t *tmpCards; // temporary array used to prevent flashing when we add more cards
float startAngle; // position of initial card, in radians
int slotCount; // number of positions where a card can be
int cardCount; // number of cards in stack
int visibleSlotCount; // number of visible slots (for culling)
int visibleDetailCount; // number of visible detail textures to show
int prefetchCardCount; // how many cards to keep in memory
bool drawDetailBelowCard; // whether detail goes above (false) or below (true) the card
// TODO(jshuma): Replace detailTexturesCentered with a detailTextureAlignment mode enum
bool detailTexturesCentered; // line up detail center and card center (instead of left edges)
bool drawCardsWithBlending; // Enable blending while drawing cards (for translucent card textures)
bool drawRuler; // whether to draw a ruler from the card to the detail texture
float radius; // carousel radius. Cards will be centered on a circle with this radius
float cardRotation; // rotation of card in XY plane relative to Z=1
bool cardsFaceTangent; // whether cards are rotated to face along a tangent to the circle
float swaySensitivity; // how much to rotate cards in relation to the rotation velocity
float frictionCoeff; // how much to slow down the carousel over time
float dragFactor; // a scale factor for how sensitive the carousel is to user dragging
int fadeInDuration; // amount of time (in ms) for smoothly switching out textures
float rezInCardCount; // this controls how rapidly distant card textures will be rez-ed in
float detailFadeRate; // rate at which details fade as they move into the distance
float4 backgroundColor;
rs_program_store programStore;
rs_program_store programStoreOpaque;
rs_program_store programStoreDetail;
rs_program_fragment singleTextureFragmentProgram;
rs_program_fragment multiTextureFragmentProgram;
rs_program_vertex vertexProgram;
rs_program_raster rasterProgram;
rs_allocation defaultTexture; // shown when no other texture is assigned
rs_allocation loadingTexture; // progress texture (shown when app is fetching the texture)
rs_allocation backgroundTexture; // drawn behind everything, if set
rs_allocation detailLineTexture; // used to draw detail line (as a quad, of course)
rs_allocation detailLoadingTexture; // used when detail texture is loading
rs_mesh defaultGeometry; // shown when no geometry is loaded
rs_mesh loadingGeometry; // shown when geometry is loading
rs_matrix4x4 projectionMatrix;
rs_matrix4x4 modelviewMatrix;
FragmentShaderConstants* shaderConstants;
rs_sampler linearClamp;

#pragma rs export_func(createCards, copyCards, lookAt)
#pragma rs export_func(doStart, doStop, doMotion, doLongPress, doSelection)
#pragma rs export_func(setTexture, setGeometry, setDetailTexture, debugCamera)
#pragma rs export_func(setCarouselRotationAngle)

// Local variables
static float bias; // rotation bias, in radians. Used for animation and dragging.
static bool updateCamera;    // force a recompute of projection and lookat matrices
static bool initialized;
static const float FLT_MAX = 1.0e37;
static int animatedSelection = -1;
static int currentFirstCard = -1;
static int64_t touchTime = -1;  // time of first touch (see doStart())
static float touchBias = 0.0f; // bias on first touch
static float2 touchPosition; // position of first touch, as defined by last call to doStart(x,y)
static float velocity = 0.0f;  // angular velocity in radians/s
static bool overscroll = false; // whether we're in the overscroll animation
static bool isDragging = false; // true while the user is dragging the carousel
static float selectionRadius = 50.0f; // movement greater than this will result in no selection
static bool enableSelection = false; // enabled until the user drags outside of selectionRadius

// Default plane of the carousel. Used for angular motion estimation in view.
static Plane carouselPlane = {
       { 0.0f, 0.0f, 0.0f }, // point
       { 0.0f, 1.0f, 0.0f }, // normal
       0.0f // plane constant (= -dot(P, N))
};

// Because allocations can't have 0 dimensions, we have to track whether or not
// cards are valid separately.
// TODO: Remove this dependency once allocations can have a zero dimension.
static bool cardAllocationValid = false;

// Default geometry when card.geometry is not set.
static const float3 cardVertices[4] = {
        { -1.0, -1.0, 0.0 },
        { 1.0, -1.0, 0.0 },
        { 1.0, 1.0, 0.0 },
        {-1.0, 1.0, 0.0 }
};

// Default camera
static PerspectiveCamera camera = {
        {2,2,2}, // from
        {0,0,0}, // at
        {0,1,0}, // up
        25.0f,   // field of view
        1.0f,    // aspect
        0.1f,    // near
        100.0f   // far
};

// Forward references
static int intersectGeometry(Ray* ray, float *bestTime);
static bool __attribute__((overloadable))
        makeRayForPixelAt(Ray* ray, PerspectiveCamera* cam, float x, float y);
static bool __attribute__((overloadable))
        makeRayForPixelAt(Ray* ray, rs_matrix4x4* model, rs_matrix4x4* proj, float x, float y);
static float deltaTimeInSeconds(int64_t current);

void init() {
    // initializers currently have a problem when the variables are exported, so initialize
    // globals here.
    if (debugTextureLoading) rsDebug("Renderscript: init()", 0);
    startAngle = 0.0f;
    slotCount = 10;
    visibleSlotCount = 1;
    visibleDetailCount = 3;
    bias = 0.0f;
    radius = 1.0f;
    cardRotation = 0.0f;
    cardsFaceTangent = false;
    updateCamera = true;
    initialized = false;
    backgroundColor = (float4) { 0.0f, 0.0f, 0.0f, 1.0f };
    cardAllocationValid = false;
    cardCount = 0;
    fadeInDuration = 250;
    rezInCardCount = 0.0f; // alpha will ramp to 1.0f over this many cards (0.0f means disabled)
    detailFadeRate = 0.5f; // fade details over this many slot positions.
}

static void updateAllocationVars(Card_t* newcards)
{
    // Cards
    rs_allocation cardAlloc = rsGetAllocation(newcards);
    // TODO: use new rsIsObject()
    cardCount = (cardAllocationValid && cardAlloc.p != 0) ? rsAllocationGetDimX(cardAlloc) : 0;
}

void createCards(int n)
{
    if (debugTextureLoading) {
        rsDebug("*** CreateCards with count", n);
    }

    // Since allocations can't have 0-size, we track validity ourselves based on the call to
    // this method.
    cardAllocationValid = n > 0;

    initialized = false;
    updateAllocationVars(cards);
}

void copyCard(Card_t* dest, Card_t * src)
{
    rsSetObject(&dest->texture, src->texture);
    rsSetObject(&dest->detailTexture, src->detailTexture);
    dest->detailTextureOffset = src->detailTextureOffset;
    dest->detailLineOffset = src->detailLineOffset;
    rsSetObject(&dest->geometry, src->geometry);
    dest->matrix = src->matrix;
    dest->textureState = src->textureState;
    dest->detailTextureState = src->detailTextureState;
    dest->geometryState = src->geometryState;
    dest->visible = src->visible;
    dest->textureTimeStamp = src->textureTimeStamp;
    dest->detailTextureTimeStamp = src->detailTextureTimeStamp;
}

void initCard(Card_t* card)
{
    static const float2 zero = {0.0f, 0.0f};
    rsClearObject(&card->texture);
    rsClearObject(&card->detailTexture);
    card->detailTextureOffset = zero;
    card->detailLineOffset = zero;
    rsClearObject(&card->geometry);
    rsMatrixLoadIdentity(&card->matrix);
    card->textureState = STATE_INVALID;
    card->detailTextureState = STATE_INVALID;
    card->geometryState = STATE_INVALID;
    card->visible = false;
    card->textureTimeStamp = 0;
    card->detailTextureTimeStamp = 0;
}

void copyCards(int n)
{
    unsigned int oldsize = cardAllocationValid ? rsAllocationGetDimX(rsGetAllocation(cards)) : 0;
    unsigned int newsize = rsAllocationGetDimX(rsGetAllocation(tmpCards));
    unsigned int copysize = min(oldsize, newsize);

    // Copy existing cards
    for (int i = 0; i < copysize; i++) {
        if (debugTextureLoading) {
            rsDebug("copying card ", i);
        }
        copyCard(tmpCards + i, cards + i);
        // Release these now so we don't have to wait for GC for cards allocation.
        // Assumes we're done with the cards allocation structure.
        rsClearObject(&cards[i].texture);
        rsClearObject(&cards[i].detailTexture);
        rsClearObject(&cards[i].geometry);
        cards[i].textureState = STATE_INVALID;
        cards[i].detailTextureState = STATE_INVALID;
        cards[i].geometryState = STATE_INVALID;
    }

    // Initialize remaining cards.
    int first = cardAllocationValid ? min(oldsize, newsize) : 0;
    for (int k = first; k < newsize; k++) {
        initCard(tmpCards + k);
    }

    // Since allocations can't have 0-size, we use the same trick as createCards() where
    // we track validity ourselves. Grrr.
    cardAllocationValid = n > 0;

    updateAllocationVars(tmpCards);
}

// Computes an alpha value for a card using elapsed time and constant fadeInDuration
float getAnimatedAlpha(int64_t startTime, int64_t currentTime)
{
    double timeElapsed = (double) (currentTime - startTime); // in ms
    double alpha = (double) timeElapsed / fadeInDuration;
    return min(1.0f, (float) alpha);
}

// Return angle for position p. Typically p will be an integer position, but can be fractional.
static float cardPosition(float p)
{
    return startAngle + bias + 2.0f * M_PI * p / slotCount;
}

// Return slot for a card in position p. Typically p will be an integer slot, but can be fractional.
static float slotPosition(float p)
{
    return startAngle + 2.0f * M_PI * p / slotCount;
}

// Returns total angle for given number of cards
static float wedgeAngle(float cards)
{
    return cards * 2.0f * M_PI / slotCount;
}

// convert from carousel rotation angle (in card slot units) to radians.
static float carouselRotationAngleToRadians(float carouselRotationAngle)
{
    return -wedgeAngle(carouselRotationAngle);
}

// convert from radians to carousel rotation angle (in card slot units).
static float radiansToCarouselRotationAngle(float angle)
{
    return -angle * slotCount / ( 2.0f * M_PI );
}


// Return the lowest slot number for a given angular position.
static int cardIndex(float angle)
{
    return floor(angle - startAngle - bias) * slotCount / (2.0f * M_PI);
}

// Set basic camera properties:
//    from - position of the camera in x,y,z
//    at - target we're looking at - used to compute view direction
//    up - a normalized vector indicating up (typically { 0, 1, 0})
//
// NOTE: the view direction and up vector cannot be parallel/antiparallel with each other
void lookAt(float fromX, float fromY, float fromZ,
        float atX, float atY, float atZ,
        float upX, float upY, float upZ)
{
    camera.from.x = fromX;
    camera.from.y = fromY;
    camera.from.z = fromZ;
    camera.at.x = atX;
    camera.at.y = atY;
    camera.at.z = atZ;
    camera.up.x = upX;
    camera.up.y = upY;
    camera.up.z = upZ;
    updateCamera = true;
}

// Load a projection matrix for the given parameters.  This is equivalent to gluPerspective()
static void loadPerspectiveMatrix(rs_matrix4x4* matrix, float fovy, float aspect, float near, float far)
{
    rsMatrixLoadIdentity(matrix);
    float top = near * tan((float) (fovy * M_PI / 360.0f));
    float bottom = -top;
    float left = bottom * aspect;
    float right = top * aspect;
    rsMatrixLoadFrustum(matrix, left, right, bottom, top, near, far);
}

// Construct a matrix based on eye point, center and up direction. Based on the
// man page for gluLookat(). Up must be normalized.
static void loadLookatMatrix(rs_matrix4x4* matrix, float3 eye, float3 center, float3 up)
{
    float3 f = normalize(center - eye);
    float3 s = normalize(cross(f, up));
    float3 u = cross(s, f);
    float m[16];
    m[0] = s.x;
    m[4] = s.y;
    m[8] = s.z;
    m[12] = 0.0f;
    m[1] = u.x;
    m[5] = u.y;
    m[9] = u.z;
    m[13] = 0.0f;
    m[2] = -f.x;
    m[6] = -f.y;
    m[10] = -f.z;
    m[14] = 0.0f;
    m[3] = m[7] = m[11] = 0.0f;
    m[15] = 1.0f;
    rsMatrixLoad(matrix, m);
    rsMatrixTranslate(matrix, -eye.x, -eye.y, -eye.z);
}

void setTexture(int n, rs_allocation texture)
{
    if (n < 0 || n >= cardCount) return;
    rsSetObject(&cards[n].texture, texture);
    cards[n].textureState = (texture.p != 0) ? STATE_LOADED : STATE_INVALID;
    cards[n].textureTimeStamp = rsUptimeMillis();
}

void setDetailTexture(int n, float offx, float offy, float loffx, float loffy, rs_allocation texture)
{
    if (n < 0 || n >= cardCount) return;
    rsSetObject(&cards[n].detailTexture, texture);
    cards[n].detailTextureOffset.x = offx;
    cards[n].detailTextureOffset.y = offy;
    cards[n].detailLineOffset.x = loffx;
    cards[n].detailLineOffset.y = loffy;
    cards[n].detailTextureState = (texture.p != 0) ? STATE_LOADED : STATE_INVALID;
    cards[n].detailTextureTimeStamp = rsUptimeMillis();
}

void setGeometry(int n, rs_mesh geometry)
{
    if (n < 0 || n >= cardCount) return;
    rsSetObject(&cards[n].geometry, geometry);
    if (cards[n].geometry.p != 0)
        cards[n].geometryState = STATE_LOADED;
    else
        cards[n].geometryState = STATE_INVALID;
}

void setCarouselRotationAngle(float carouselRotationAngle) {
    bias = carouselRotationAngleToRadians(carouselRotationAngle);
}

static float3 getAnimatedScaleForSelected()
{
    int64_t dt = (rsUptimeMillis() - touchTime);
    float fraction = (dt < ANIMATION_SCALE_TIME) ? (float) dt / ANIMATION_SCALE_TIME : 1.0f;
    const float3 one = { 1.0f, 1.0f, 1.0f };
    return one + fraction * SELECTED_SCALE_FACTOR;
}

// The Verhulst logistic function: http://en.wikipedia.org/wiki/Logistic_function
//    P(t) = 1 / (1 + e^(-t))
// Parameter t: Any real number
// Returns: A float in the range (0,1), with P(0.5)=0
static float logistic(float t) {
    return 1.f / (1.f + exp(-t));
}

static float getSwayAngleForVelocity(float v, bool enableSway)
{
    float sway = 0.0f;

    if (enableSway) {
        const float range = M_PI * 2./3.; // How far we can deviate from center, peak-to-peak
        sway = range * (logistic(-v * swaySensitivity) - 0.5f);
    }

    return sway;
}

// matrix: The output matrix.
// i: The card we're getting the matrix for.
// enableSway: Whether to enable swaying. (We want it on for cards, and off for detail textures.)
static void getMatrixForCard(rs_matrix4x4* matrix, int i, bool enableSway)
{
    float theta = cardPosition(i);
    float swayAngle = getSwayAngleForVelocity(velocity, enableSway);
    rsMatrixRotate(matrix, degrees(theta), 0, 1, 0);
    rsMatrixTranslate(matrix, radius, 0, 0);
    float rotation = cardRotation + swayAngle;
    if (!cardsFaceTangent) {
      rotation -= theta;
    }
    rsMatrixRotate(matrix, degrees(rotation), 0, 1, 0);
    if (i == animatedSelection && enableSelection) {
        float3 scale = getAnimatedScaleForSelected();
        rsMatrixScale(matrix, scale.x, scale.y, scale.z);
    }
    // TODO: apply custom matrix for cards[i].geometry
}

/*
 * Draws cards around the Carousel.
 * Returns true if we're still animating any property of the cards (e.g. fades).
 */
static bool drawCards(int64_t currentTime)
{
    const float wedgeAngle = 2.0f * M_PI / slotCount;
    const float endAngle = startAngle + visibleSlotCount * wedgeAngle;
    bool stillAnimating = false;
    for (int i = cardCount-1; i >= 0; i--) {
        if (cards[i].visible) {
            // If this card was recently loaded, this will be < 1.0f until the animation completes
            float animatedAlpha = getAnimatedAlpha(cards[i].textureTimeStamp, currentTime);
            if (animatedAlpha < 1.0f) {
                stillAnimating = true;
            }

            // Compute fade out for cards in the distance
            float positionAlpha;
            if (rezInCardCount > 0.0f) {
                positionAlpha = (endAngle - cardPosition(i)) / wedgeAngle;
                positionAlpha = min(1.0f, positionAlpha / rezInCardCount);
            } else {
                positionAlpha = 1.0f;
            }

            // Set alpha for blending between the textures
            shaderConstants->fadeAmount = min(1.0f, animatedAlpha * positionAlpha);
            rsAllocationMarkDirty(rsGetAllocation(shaderConstants));

            // Bind the appropriate shader network.  If there's no alpha blend, then
            // switch to single shader for better performance.
            const bool loaded = cards[i].textureState == STATE_LOADED;
            if (shaderConstants->fadeAmount == 1.0f || shaderConstants->fadeAmount < 0.01f) {
                rsgBindProgramFragment(singleTextureFragmentProgram);
                rsgBindTexture(singleTextureFragmentProgram, 0,
                        (loaded && shaderConstants->fadeAmount == 1.0f) ?
                        cards[i].texture : loadingTexture);
            } else {
                rsgBindProgramFragment(multiTextureFragmentProgram);
                rsgBindTexture(multiTextureFragmentProgram, 0, loadingTexture);
                rsgBindTexture(multiTextureFragmentProgram, 1, loaded ?
                        cards[i].texture : loadingTexture);
            }

            // Draw geometry
            rs_matrix4x4 matrix = modelviewMatrix;
            getMatrixForCard(&matrix, i, true);
            rsgProgramVertexLoadModelMatrix(&matrix);
            if (cards[i].geometryState == STATE_LOADED && cards[i].geometry.p != 0) {
                rsgDrawMesh(cards[i].geometry);
            } else if (cards[i].geometryState == STATE_LOADING && loadingGeometry.p != 0) {
                rsgDrawMesh(loadingGeometry);
            } else if (defaultGeometry.p != 0) {
                rsgDrawMesh(defaultGeometry);
            } else {
                // Draw place-holder geometry
                rsgDrawQuad(
                    cardVertices[0].x, cardVertices[0].y, cardVertices[0].z,
                    cardVertices[1].x, cardVertices[1].y, cardVertices[1].z,
                    cardVertices[2].x, cardVertices[2].y, cardVertices[2].z,
                    cardVertices[3].x, cardVertices[3].y, cardVertices[3].z);
            }
        }
    }
    return stillAnimating;
}

/**
 * Convert projection from normalized coordinates to pixel coordinates.
 *
 * @return True on success, false on failure.
 */
static bool convertNormalizedToPixelCoordinates(float4 *screenCoord, float width, float height) {
    // This is probably cheaper than pre-multiplying with another matrix.
    if (screenCoord->w == 0.0f) {
        rsDebug("Bad transform while converting from normalized to pixel coordinates: ",
            screenCoord);
        return false;
    }
    *screenCoord *= 1.0f / screenCoord->w;
    screenCoord->x += 1.0f;
    screenCoord->y += 1.0f;
    screenCoord->z += 1.0f;
    screenCoord->x = round(screenCoord->x * 0.5f * width);
    screenCoord->y = round(screenCoord->y * 0.5f * height);
    screenCoord->z = - 0.5f * screenCoord->z;
    return true;
}

/*
 * Draws a screen-aligned card with the exact dimensions from the detail texture.
 * This is used to display information about the object being displayed above the geomertry.
 * Returns true if we're still animating any property of the cards (e.g. fades).
 */
static bool drawDetails(int64_t currentTime)
{
    const float width = rsgGetWidth();
    const float height = rsgGetHeight();

    bool stillAnimating = false;

    // We'll be drawing in screen space, sampled on pixel centers
    rs_matrix4x4 projection, model;
    rsMatrixLoadOrtho(&projection, 0.0f, width, 0.0f, height, 0.0f, 1.0f);
    rsgProgramVertexLoadProjectionMatrix(&projection);
    rsMatrixLoadIdentity(&model);
    rsgProgramVertexLoadModelMatrix(&model);
    updateCamera = true; // we messed with the projection matrix. Reload on next pass...

    const float yPadding = 5.0f; // draw line this far (in pixels) away from top and geometry

    // This can be done once...
    rsgBindTexture(multiTextureFragmentProgram, 0, detailLoadingTexture);

    const float wedgeAngle = 2.0f * M_PI / slotCount;
    // Angle where details start fading from 1.0f
    const float startDetailFadeAngle = startAngle + (visibleDetailCount - 1) * wedgeAngle;
    // Angle where detail alpha is 0.0f
    const float endDetailFadeAngle = startDetailFadeAngle + detailFadeRate * wedgeAngle;

    for (int i = cardCount-1; i >= 0; --i) {
        if (cards[i].visible) {
            if (cards[i].detailTextureState == STATE_LOADED && cards[i].detailTexture.p != 0) {
                const float lineWidth = rsAllocationGetDimX(detailLineTexture);

                // Compute position in screen space of top corner or bottom corner of card
                rsMatrixLoad(&model, &modelviewMatrix);
                getMatrixForCard(&model, i, false);
                rs_matrix4x4 matrix;
                rsMatrixLoadMultiply(&matrix, &projectionMatrix, &model);

                int indexLeft, indexRight;
                float4 screenCoord;
                if (drawDetailBelowCard) {
                    indexLeft = 0;
                    indexRight = 1;
                } else {
                    indexLeft = 3;
                    indexRight = 2;
                }
                float4 screenCoordLeft = rsMatrixMultiply(&matrix, cardVertices[indexLeft]);
                float4 screenCoordRight = rsMatrixMultiply(&matrix, cardVertices[indexRight]);
                if (screenCoordLeft.w == 0.0f || screenCoordRight.w == 0.0f) {
                    // this shouldn't happen
                    rsDebug("Bad transform: ", screenCoord);
                    continue;
                }
                (void) convertNormalizedToPixelCoordinates(&screenCoordLeft, width, height);
                (void) convertNormalizedToPixelCoordinates(&screenCoordRight, width, height);
                if (debugDetails) {
                    RS_DEBUG(screenCoordLeft);
                    RS_DEBUG(screenCoordRight);
                }
                screenCoord = screenCoordLeft;
                if (drawDetailBelowCard) {
                    screenCoord.y = min(screenCoordLeft.y, screenCoordRight.y);
                }
                if (detailTexturesCentered) {
                    screenCoord.x += (screenCoordRight.x - screenCoordLeft.x) / 2. -
                        rsAllocationGetDimX(cards[i].detailTexture) / 2.;
                }

                // Compute alpha for gradually fading in details. Applied to both line and
                // detail texture. TODO: use a separate background texture for line.
                float animatedAlpha = getAnimatedAlpha(cards[i].detailTextureTimeStamp, currentTime);
                if (animatedAlpha < 1.0f) {
                    stillAnimating = true;
                }

                // Compute alpha based on position. We fade cards quickly so they cannot overlap
                float positionAlpha = ((float)endDetailFadeAngle - cardPosition(i))
                        / (endDetailFadeAngle - startDetailFadeAngle);
                positionAlpha = max(0.0f, positionAlpha);
                positionAlpha = min(1.0f, positionAlpha);

                const float blendedAlpha = min(1.0f, animatedAlpha * positionAlpha);

                if (blendedAlpha == 0.0f) continue; // nothing to draw
                if (blendedAlpha == 1.0f) {
                    rsgBindProgramFragment(singleTextureFragmentProgram);
                } else {
                    rsgBindProgramFragment(multiTextureFragmentProgram);
                }

                // Set alpha for blending between the textures
                shaderConstants->fadeAmount = blendedAlpha;
                rsAllocationMarkDirty(rsGetAllocation(shaderConstants));

                // Draw line from upper left card corner to the top of the screen
                if (drawRuler) {
                    const float halfWidth = lineWidth * 0.5f;
                    const float rulerTop = drawDetailBelowCard ? screenCoord.y : height;
                    const float rulerBottom = drawDetailBelowCard ? 0 : screenCoord.y;
                    const float x0 = cards[i].detailLineOffset.x + screenCoord.x - halfWidth;
                    const float x1 = cards[i].detailLineOffset.x + screenCoord.x + halfWidth;
                    const float y0 = rulerBottom + yPadding;
                    const float y1 = rulerTop - yPadding - cards[i].detailLineOffset.y;

                    if (blendedAlpha == 1.0f) {
                        rsgBindTexture(singleTextureFragmentProgram, 0, detailLineTexture);
                    } else {
                        rsgBindTexture(multiTextureFragmentProgram, 1, detailLineTexture);
                    }
                    rsgDrawQuad(x0, y0, screenCoord.z,  x1, y0, screenCoord.z,
                            x1, y1, screenCoord.z,  x0, y1, screenCoord.z);
                }

                // Draw the detail texture next to it using the offsets provided.
                const float textureWidth = rsAllocationGetDimX(cards[i].detailTexture);
                const float textureHeight = rsAllocationGetDimY(cards[i].detailTexture);
                const float offx = cards[i].detailTextureOffset.x;
                const float offy = -cards[i].detailTextureOffset.y;
                const float textureTop = drawDetailBelowCard ? screenCoord.y : height;
                const float x0 = cards[i].detailLineOffset.x + screenCoord.x + offx;
                const float x1 = cards[i].detailLineOffset.x + screenCoord.x + offx + textureWidth;
                const float y0 = textureTop + offy - textureHeight - cards[i].detailLineOffset.y;
                const float y1 = textureTop + offy - cards[i].detailLineOffset.y;

                if (blendedAlpha == 1.0f) {
                    rsgBindTexture(singleTextureFragmentProgram, 0, cards[i].detailTexture);
                } else {
                    rsgBindTexture(multiTextureFragmentProgram, 1, cards[i].detailTexture);
                }
                rsgDrawQuad(x0, y0, screenCoord.z,  x1, y0, screenCoord.z,
                        x1, y1, screenCoord.z,  x0, y1, screenCoord.z);
            }
        }
    }
    return stillAnimating;
}

static void drawBackground()
{
    static bool toggle;
    if (backgroundTexture.p != 0) {
        rsgClearDepth(1.0f);
        rs_matrix4x4 projection, model;
        rsMatrixLoadOrtho(&projection, -1.0f, 1.0f, -1.0f, 1.0f, 0.0f, 1.0f);
        rsgProgramVertexLoadProjectionMatrix(&projection);
        rsMatrixLoadIdentity(&model);
        rsgProgramVertexLoadModelMatrix(&model);
        rsgBindTexture(singleTextureFragmentProgram, 0, backgroundTexture);
        float z = -0.9999f;
        rsgDrawQuad(
            cardVertices[0].x, cardVertices[0].y, z,
            cardVertices[1].x, cardVertices[1].y, z,
            cardVertices[2].x, cardVertices[2].y, z,
            cardVertices[3].x, cardVertices[3].y, z);
        updateCamera = true; // we mucked with the matrix.
    } else {
        rsgClearDepth(1.0f);
        if (debugRendering) { // for debugging - flash the screen so we know we're still rendering
            rsgClearColor(toggle ? backgroundColor.x : 1.0f,
                        toggle ? backgroundColor.y : 0.0f,
                        toggle ? backgroundColor.z : 0.0f,
                        backgroundColor.w);
            toggle = !toggle;
        } else {
           rsgClearColor(backgroundColor.x, backgroundColor.y, backgroundColor.z,
                   backgroundColor.w);
       }
    }
}

static void updateCameraMatrix(float width, float height)
{
    float aspect = width / height;
    if (aspect != camera.aspect || updateCamera) {
        camera.aspect = aspect;
        loadPerspectiveMatrix(&projectionMatrix, camera.fov, camera.aspect, camera.near, camera.far);
        rsgProgramVertexLoadProjectionMatrix(&projectionMatrix);

        loadLookatMatrix(&modelviewMatrix, camera.from, camera.at, camera.up);
        rsgProgramVertexLoadModelMatrix(&modelviewMatrix);
        updateCamera = false;
    }
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Behavior/Physics
////////////////////////////////////////////////////////////////////////////////////////////////////
static int64_t lastTime = 0L; // keep track of how much time has passed between frames
static float2 lastPosition;
static bool animating = false;
static float velocityThreshold = 0.1f * M_PI / 180.0f;
static float velocityTracker;
static int velocityTrackerCount;
static float mass = 5.0f; // kg

static const float G = 9.80f; // gravity constant, in m/s
static const float springConstant = 0.0f;

static float dragFunction(float x, float y)
{
    return dragFactor * ((x - lastPosition.x) / rsgGetWidth()) * M_PI;
}

static float deltaTimeInSeconds(int64_t current)
{
    return (lastTime > 0L) ? (float) (current - lastTime) / 1000.0f : 0.0f;
}

int doSelection(float x, float y)
{
    Ray ray;
    if (makeRayForPixelAt(&ray, &camera, x, y)) {
        float bestTime = FLT_MAX;
        return intersectGeometry(&ray, &bestTime);
    }
    return -1;
}

void sendAnimationStarted() {
    rsSendToClient(CMD_ANIMATION_STARTED);
}

void sendAnimationFinished() {
    float data[1];
    data[0] = radiansToCarouselRotationAngle(bias);
    rsSendToClient(CMD_ANIMATION_FINISHED, (int*) data, sizeof(data));
}

void doStart(float x, float y)
{
    touchPosition = lastPosition = (float2) { x, y };
    velocity = 0.0f;
    velocityTracker = 0.0f;
    velocityTrackerCount = 0;
    touchTime = rsUptimeMillis();
    touchBias = bias;
    isDragging = true;
    enableSelection = true;
    animatedSelection = doSelection(x, y); // used to provide visual feedback on touch
}

void doStop(float x, float y)
{
    int64_t currentTime = rsUptimeMillis();
    updateAllocationVars(cards);

    if (enableSelection) {
        int data[1];
        int selection = doSelection(x, y);
        if (selection != -1) {
            if (debugSelection) rsDebug("Selected item on doStop():", selection);
            data[0] = selection;
            rsSendToClientBlocking(CMD_CARD_SELECTED, data, sizeof(data));
        }
        animating = false;
    } else {
        // TODO: move velocity tracking to Java
        velocity = velocityTrackerCount > 0 ?
                    (velocityTracker / velocityTrackerCount) : 0.0f;  // avg velocity
        if (fabs(velocity) > velocityThreshold) {
            animating = true;
        }
    }
    enableSelection = false;
    lastTime = rsUptimeMillis();
    isDragging = false;
}

void doLongPress()
{
    int64_t currentTime = rsUptimeMillis();
    updateAllocationVars(cards);
    // Selection happens for most recent position detected in doMotion()
    int selection = doSelection(lastPosition.x, lastPosition.y);
    if (selection != -1) {
        if (debugSelection) rsDebug("doLongPress(), selection = ", selection);
        int data[1];
        data[0] = selection;
        rsSendToClientBlocking(CMD_CARD_LONGPRESS, data, sizeof(data));
    }
    lastTime = rsUptimeMillis();
}

void doMotion(float x, float y)
{
    const float firstBias = wedgeAngle(0.0f);
    const float lastBias = -max(0.0f, wedgeAngle(cardCount - visibleDetailCount));
    int64_t currentTime = rsUptimeMillis();
    float deltaOmega = dragFunction(x, y);
    if (!enableSelection) {
        bias += deltaOmega;
        bias = clamp(bias, lastBias - wedgeAngle(OVERSCROLL_SLOTS),
                firstBias + wedgeAngle(OVERSCROLL_SLOTS));
    }
    const float2 delta = (float2) { x, y } - touchPosition;
    float distance = sqrt(dot(delta, delta));
    bool inside = (distance < selectionRadius);
    enableSelection &= inside;
    lastPosition = (float2) { x, y };
    float dt = deltaTimeInSeconds(currentTime);
    if (dt > 0.0f) {
        float v = deltaOmega / dt;
        velocityTracker += v;
        velocityTrackerCount++;
    }
    velocity = velocityTrackerCount > 0 ?
                (velocityTracker / velocityTrackerCount) : 0.0f;  // avg velocity
    lastTime = currentTime;
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Hit detection using ray casting.
////////////////////////////////////////////////////////////////////////////////////////////////////
static const float EPSILON = 1.0e-6f;
static const float tmin = 0.0f;

static bool
rayTriangleIntersect(Ray* ray, float3 p0, float3 p1, float3 p2, float* tout)
{
    float3 e1 = p1 - p0;
    float3 e2 = p2 - p0;
    float3 s1 = cross(ray->direction, e2);

    float div = dot(s1, e1);
    if (div == 0.0f) return false;  // ray is parallel to plane.

    float3 d = ray->position - p0;
    float invDiv = 1.0f / div;

    float u = dot(d, s1) * invDiv;
    if (u < 0.0f || u > 1.0f) return false;

    float3 s2 = cross(d, e1);
    float v = dot(ray->direction, s2) * invDiv;
    if ( v < 0.0f || (u+v) > 1.0f) return false;

    float t = dot(e2, s2) * invDiv;
    if (t < tmin || t > *tout)
        return false;
    *tout = t;
    return true;
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Computes ray/plane intersection. Returns false if no intersection found.
////////////////////////////////////////////////////////////////////////////////////////////////////
static bool
rayPlaneIntersect(Ray* ray, Plane* plane, float* tout)
{
    float denom = dot(ray->direction, plane->normal);
    if (fabs(denom) > EPSILON) {
        float t = - (plane->constant + dot(ray->position, plane->normal)) / denom;
        if (t > tmin && t < *tout) {
            *tout = t;
            return true;
        }
    }
    return false;
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Computes ray/cylindr intersection. There are 0, 1 or 2 hits.
// Returns true and sets *tout to the closest point or
// returns false if no intersection found.
////////////////////////////////////////////////////////////////////////////////////////////////////
static bool
rayCylinderIntersect(Ray* ray, Cylinder* cylinder, float* tout)
{
    const float A = ray->direction.x * ray->direction.x + ray->direction.z * ray->direction.z;
    if (A < EPSILON) return false; // ray misses

    // Compute quadratic equation coefficients
    const float B = 2.0f * (ray->direction.x * ray->position.x
            + ray->direction.z * ray->position.z);
    const float C = ray->position.x * ray->position.x
            + ray->position.z * ray->position.z
            - cylinder->radius * cylinder->radius;
    float disc = B*B - 4*A*C;

    if (disc < 0.0f) return false; // ray misses
    disc = sqrt(disc);
    const float denom = 2.0f * A;

    // Nearest point
    const float t1 = (-B - disc) / denom;
    if (t1 > tmin && t1 < *tout) {
        *tout = t1;
        return true;
    }

    // Far point
    const float t2 = (-B + disc) / denom;
    if (t2 > tmin && t2 < *tout) {
        *tout = t2;
        return true;
    }
    return false;
}

// Creates a ray for an Android pixel coordinate given a camera, ray and coordinates.
// Note that the Y coordinate is opposite of GL rendering coordinates.
static bool __attribute__((overloadable))
makeRayForPixelAt(Ray* ray, PerspectiveCamera* cam, float x, float y)
{
    if (debugCamera) {
        rsDebug("------ makeRay() -------", 0);
        rsDebug("Camera.from:", cam->from);
        rsDebug("Camera.at:", cam->at);
        rsDebug("Camera.dir:", normalize(cam->at - cam->from));
    }

    // Vector math.  This has the potential to be much faster.
    // TODO: pre-compute lowerLeftRay, du, dv to eliminate most of this math.
    const float u = x / rsgGetWidth();
    const float v = 1.0f - (y / rsgGetHeight());
    const float aspect = (float) rsgGetWidth() / rsgGetHeight();
    const float tanfov2 = 2.0f * tan(radians(cam->fov / 2.0f));
    float3 dir = normalize(cam->at - cam->from);
    float3 du = tanfov2 * normalize(cross(dir, cam->up));
    float3 dv = tanfov2 * normalize(cross(du, dir));
    du *= aspect;
    float3 lowerLeftRay = dir - (0.5f * du) - (0.5f * dv);
    const float3 rayPoint = cam->from;
    const float3 rayDir = normalize(lowerLeftRay + u*du + v*dv);
    if (debugCamera) {
        rsDebug("Ray direction (vector math) = ", rayDir);
    }

    ray->position =  rayPoint;
    ray->direction = rayDir;
    return true;
}

// Creates a ray for an Android pixel coordinate given a model view and projection matrix.
// Note that the Y coordinate is opposite of GL rendering coordinates.
static bool __attribute__((overloadable))
makeRayForPixelAt(Ray* ray, rs_matrix4x4* model, rs_matrix4x4* proj, float x, float y)
{
    rs_matrix4x4 pm = *model;
    rsMatrixLoadMultiply(&pm, proj, model);
    if (!rsMatrixInverse(&pm)) {
        rsDebug("ERROR: SINGULAR PM MATRIX", 0);
        return false;
    }
    const float width = rsgGetWidth();
    const float height = rsgGetHeight();
    const float winx = 2.0f * x / width - 1.0f;
    const float winy = 2.0f * y / height - 1.0f;

    float4 eye = { 0.0f, 0.0f, 0.0f, 1.0f };
    float4 at = { winx, winy, 1.0f, 1.0f };

    eye = rsMatrixMultiply(&pm, eye);
    eye *= 1.0f / eye.w;

    at = rsMatrixMultiply(&pm, at);
    at *= 1.0f / at.w;

    const float3 rayPoint = { eye.x, eye.y, eye.z };
    const float3 atPoint = { at.x, at.y, at.z };
    const float3 rayDir = normalize(atPoint - rayPoint);
    if (debugCamera) {
        rsDebug("winx: ", winx);
        rsDebug("winy: ", winy);
        rsDebug("Ray position (transformed) = ", eye);
        rsDebug("Ray direction (transformed) = ", rayDir);
    }
    ray->position =  rayPoint;
    ray->direction = rayDir;
    return true;
}

static int intersectGeometry(Ray* ray, float *bestTime)
{
    int hit = -1;
    for (int id = 0; id < cardCount; id++) {
        if (cards[id].visible) {
            rs_matrix4x4 matrix;
            float3 p[4];

            // Transform card vertices to world space
            rsMatrixLoadIdentity(&matrix);
            getMatrixForCard(&matrix, id, true);
            for (int vertex = 0; vertex < 4; vertex++) {
                float4 tmp = rsMatrixMultiply(&matrix, cardVertices[vertex]);
                if (tmp.w != 0.0f) {
                    p[vertex].x = tmp.x;
                    p[vertex].y = tmp.y;
                    p[vertex].z = tmp.z;
                    p[vertex] *= 1.0f / tmp.w;
                } else {
                    rsDebug("Bad w coord: ", tmp);
                }
            }

            // Intersect card geometry
            if (rayTriangleIntersect(ray, p[0], p[1], p[2], bestTime)
                || rayTriangleIntersect(ray, p[2], p[3], p[0], bestTime)) {
                hit = id;
            }
        }
    }
    return hit;
}

// This method computes the position of all the cards by updating bias based on a
// simple physics model.  If the cards are still in motion, returns true.
static bool doPhysics(float dt)
{
    const float minStepTime = 1.0f / 300.0f; // ~5 steps per frame
    const int N = (dt > minStepTime) ? (1 + round(dt / minStepTime)) : 1;
    dt /= N;
    for (int i = 0; i < N; i++) {
        // Force friction - always opposes motion
        const float Ff = -frictionCoeff * velocity;

        // Restoring force to match cards with slots
        const float theta = startAngle + bias;
        const float dtheta = 2.0f * M_PI / slotCount;
        const float position = theta / dtheta;
        const float fraction = position - floor(position); // fractional position between slots
        float x;
        if (fraction > 0.5f) {
            x = - (1.0f - fraction);
        } else {
            x = fraction;
        }
        const float Fr = - springConstant * x;

        // compute velocity
        const float momentum = mass * velocity + (Ff + Fr)*dt;
        velocity = momentum / mass;
        bias += velocity * dt;
    }
    return fabs(velocity) > velocityThreshold;
}

static float easeOut(float x)
{
    return x;
}

// Computes the next value for bias using the current animation (physics or overscroll)
static bool updateNextPosition(int64_t currentTime)
{
    static const float biasMin = 1e-4f; // close enough if we're within this margin of result

    float dt = deltaTimeInSeconds(currentTime);

    if (dt <= 0.0f) {
        if (debugRendering) rsDebug("Time delta was <= 0", dt);
        return true;
    }

    const float firstBias = wedgeAngle(0.0f);
    const float lastBias = -max(0.0f, wedgeAngle(cardCount - visibleDetailCount));
    bool stillAnimating = false;
    if (overscroll) {
        if (bias > firstBias) {
            bias -= 4.0f * dt * easeOut((bias - firstBias) * 2.0f);
            if (fabs(bias - firstBias) < biasMin) {
                bias = firstBias;
            } else {
                stillAnimating = true;
            }
        } else if (bias < lastBias) {
            bias += 4.0f * dt * easeOut((lastBias - bias) * 2.0f);
            if (fabs(bias - lastBias) < biasMin) {
                bias = lastBias;
            } else {
                stillAnimating = true;
            }
        } else {
            overscroll = false;
        }
    } else {
        stillAnimating = doPhysics(dt);
        overscroll = bias > firstBias || bias < lastBias;
        if (overscroll) {
            velocity = 0.0f; // prevent bouncing due to v > 0 after overscroll animation.
        }
    }
    float newbias = clamp(bias, lastBias - wedgeAngle(OVERSCROLL_SLOTS),
            firstBias + wedgeAngle(OVERSCROLL_SLOTS));
    if (newbias != bias) { // we clamped
        velocity = 0.0f;
        overscroll = true;
    }
    bias = newbias;
    return stillAnimating;
}

// Cull cards based on visibility and visibleSlotCount.
// If visibleSlotCount is > 0, then only show those slots and cull the rest.
// Otherwise, it should cull based on bounds of geometry.
static int cullCards()
{
    // TODO(jshuma): Instead of fully fetching prefetchCardCount cards, make a distinction between
    // STATE_LOADED and a new STATE_PRELOADING, which will keep the textures loaded but will not
    // attempt to actually draw them.
    const int prefetchCardCountPerSide = prefetchCardCount / 2;
    const float thetaFirst = slotPosition(-prefetchCardCountPerSide);
    const float thetaSelected = slotPosition(0);
    const float thetaHalfAngle = (thetaSelected - thetaFirst) * 0.5f;
    const float thetaSelectedLow = thetaSelected - thetaHalfAngle;
    const float thetaSelectedHigh = thetaSelected + thetaHalfAngle;
    const float thetaLast = slotPosition(visibleSlotCount - 1 + prefetchCardCountPerSide);

    int count = 0;
    for (int i = 0; i < cardCount; i++) {
        if (visibleSlotCount > 0) {
            // If visibleSlotCount is specified, then only show up to visibleSlotCount cards.
            float p = cardPosition(i);
            if (p >= thetaFirst && p < thetaLast) {
                cards[i].visible = true;
                count++;
            } else {
                cards[i].visible = false;
            }
        } else {
            // Cull the rest of the cards using bounding box of geometry.
            // TODO
            cards[i].visible = true;
            count++;
        }
    }
    return count;
}

// Request texture/geometry for items that have come into view
// or doesn't have a texture yet.
static void updateCardResources(int64_t currentTime)
{
    for (int i = cardCount-1; i >= 0; --i) {
        int data[1];
        if (cards[i].visible) {
            if (debugTextureLoading) rsDebug("*** Texture stamp: ", (int)cards[i].textureTimeStamp);

            // request texture from client if not loaded
            if (cards[i].textureState == STATE_INVALID) {
                data[0] = i;
                bool enqueued = rsSendToClient(CMD_REQUEST_TEXTURE, data, sizeof(data));
                if (enqueued) {
                    cards[i].textureState = STATE_LOADING;
                } else {
                    if (debugTextureLoading) rsDebug("Couldn't send CMD_REQUEST_TEXTURE", 0);
                }
            }
            // request detail texture from client if not loaded
            if (cards[i].detailTextureState == STATE_INVALID) {
                data[0] = i;
                bool enqueued = rsSendToClient(CMD_REQUEST_DETAIL_TEXTURE, data, sizeof(data));
                if (enqueued) {
                    cards[i].detailTextureState = STATE_LOADING;
                } else {
                    if (debugTextureLoading) rsDebug("Couldn't send CMD_REQUEST_DETAIL_TEXTURE", 0);
                }
            }
            // request geometry from client if not loaded
            if (cards[i].geometryState == STATE_INVALID) {
                data[0] = i;
                bool enqueued = rsSendToClient(CMD_REQUEST_GEOMETRY, data, sizeof(data));
                if (enqueued) {
                    cards[i].geometryState = STATE_LOADING;
                } else {
                    if (debugGeometryLoading) rsDebug("Couldn't send CMD_REQUEST_GEOMETRY", 0);
                }
            }
        } else {
            // ask the host to remove the texture
            if (cards[i].textureState != STATE_INVALID) {
                data[0] = i;
                bool enqueued = rsSendToClient(CMD_INVALIDATE_TEXTURE, data, sizeof(data));
                if (enqueued) {
                    cards[i].textureState = STATE_INVALID;
                    cards[i].textureTimeStamp = currentTime;
                } else {
                    if (debugTextureLoading) rsDebug("Couldn't send CMD_INVALIDATE_TEXTURE", 0);
                }
            }
            // ask the host to remove the detail texture
            if (cards[i].detailTextureState != STATE_INVALID) {
                data[0] = i;
                bool enqueued = rsSendToClient(CMD_INVALIDATE_DETAIL_TEXTURE, data, sizeof(data));
                if (enqueued) {
                    cards[i].detailTextureState = STATE_INVALID;
                    cards[i].detailTextureTimeStamp = currentTime;
                } else {
                    if (debugTextureLoading) rsDebug("Can't send CMD_INVALIDATE_DETAIL_TEXTURE", 0);
                }
            }
            // ask the host to remove the geometry
            if (cards[i].geometryState != STATE_INVALID) {
                data[0] = i;
                bool enqueued = rsSendToClient(CMD_INVALIDATE_GEOMETRY, data, sizeof(data));
                if (enqueued) {
                    cards[i].geometryState = STATE_INVALID;
                } else {
                    if (debugGeometryLoading) rsDebug("Couldn't send CMD_INVALIDATE_GEOMETRY", 0);
                }
            }
        }
    }
}

// Places dots on geometry to visually inspect that objects can be seen by rays.
// NOTE: the color of the dot is somewhat random, as it depends on texture of previously-rendered
// card.
static void renderWithRays()
{
    const float w = rsgGetWidth();
    const float h = rsgGetHeight();
    const int skip = 8;
    color(1.0f, 0.0f, 0.0f, 1.0f);
    for (int j = 0; j < (int) h; j+=skip) {
        float posY = (float) j;
        for (int i = 0; i < (int) w; i+=skip) {
            float posX = (float) i;
            Ray ray;
            if (makeRayForPixelAt(&ray, &camera, posX, posY)) {
                float bestTime = FLT_MAX;
                if (intersectGeometry(&ray, &bestTime) != -1) {
                    rsgDrawSpriteScreenspace(posX, h - posY - 1, 0.0f, 2.0f, 2.0f);
                }
            }
        }
    }
}

int root() {
    int64_t currentTime = rsUptimeMillis();

    rsgBindProgramVertex(vertexProgram);
    rsgBindProgramRaster(rasterProgram);
    rsgBindSampler(singleTextureFragmentProgram, 0, linearClamp);
    rsgBindSampler(multiTextureFragmentProgram, 0, linearClamp);
    rsgBindSampler(multiTextureFragmentProgram, 1, linearClamp);

    updateAllocationVars(cards);

    if (!initialized) {
        if (debugTextureLoading) {
            rsDebug("*** initialized was false, updating all cards (cards = ", cards);
        }
        for (int i = 0; i < cardCount; i++) {
            initCard(cards + i);
        }
        initialized = true;
    }

    rsgBindProgramFragment(singleTextureFragmentProgram);
    rsgBindProgramStore(programStoreOpaque);
    drawBackground();

    updateCameraMatrix(rsgGetWidth(), rsgGetHeight());

    bool stillAnimating = (currentTime - touchTime) <= ANIMATION_SCALE_TIME;

    if (!isDragging && animating) {
        stillAnimating = updateNextPosition(currentTime);
    }

    lastTime = currentTime;

    cullCards();

    updateCardResources(currentTime);

    // Draw cards opaque only if requested, and always draw detail textures with blending.
    if (drawCardsWithBlending) {
        rsgBindProgramStore(programStore);
    } else {
        // programStoreOpaque is already bound
    }
    stillAnimating |= drawCards(currentTime);
    rsgBindProgramStore(programStoreDetail);
    stillAnimating |= drawDetails(currentTime);

    if (stillAnimating != animating) {
        if (stillAnimating) {
            // we just started animating
            sendAnimationStarted();
        } else {
            // we were animating but stopped animating just now
            sendAnimationFinished();
        }
        animating = stillAnimating;
    }

    if (debugRays) {
        renderWithRays();
    }

    //rsSendToClient(CMD_PING);

    return animating ? 1 : 0;
}