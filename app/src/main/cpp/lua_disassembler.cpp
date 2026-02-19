#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <sstream>
#include <iomanip>
#include <cstdio>
#include <cstdint>

// Lua 5.3 Opcode Map
const char* lua_opnames[] = {
    "MOVE", "LOADK", "LOADKX", "LOADBOOL", "LOADNIL", "GETUPVAL",
    "GETTABUP", "GETTABLE", "SETTABUP", "SETUPVAL", "SETTABLE",
    "NEWTABLE", "SELF", "ADD", "SUB", "MUL", "MOD", "POW", "DIV",
    "IDIV", "BAND", "BOR", "BXOR", "SHL", "SHR",
    "UNM", "BNOT", "NOT", "LEN", "CONCAT", "JMP", "EQ", "LT", "LE",
    "TEST", "TESTSET", "CALL", "TAILCALL", "RETURN", "FORLOOP",
    "FORPREP", "TFORCALL", "TFORLOOP", "SETLIST", "CLOSURE", "VARARG",
    "EXTRAARG", NULL
};

// Basic structure of a Lua 5.3 Bytecode Instruction (32-bit)
// OPCODE: 6 bits, A: 8 bits, B: 9 bits, C: 9 bits, Ax: 26 bits, Bx: 18 bits, sBx: 18 bits
#define GET_OPCODE(i)   ((i) & 0x3F)
#define GETARG_A(i)     (((i) >> 6) & 0xFF)
#define GETARG_B(i)     (((i) >> 23) & 0x1FF)
#define GETARG_C(i)     (((i) >> 14) & 0x1FF)
#define GETARG_Bx(i)    (((i) >> 14) & 0x3FFFF)
#define GETARG_sBx(i)   (GETARG_Bx(i) - 131071)

extern "C"
JNIEXPORT void JNICALL
Java_com_techted89_gameex_NativeScanner_disassembleScript(
        JNIEnv* env,
        jobject,
        jstring inPath,
        jstring outPath) {

    if (inPath == nullptr || outPath == nullptr) return;

    const char* inC = env->GetStringUTFChars(inPath, nullptr);
    const char* outC = env->GetStringUTFChars(outPath, nullptr);

    if (inC == nullptr || outC == nullptr) {
        if (inC) env->ReleaseStringUTFChars(inPath, inC);
        if (outC) env->ReleaseStringUTFChars(outPath, outC);
        return;
    }

    FILE* fIn = fopen(inC, "rb");
    FILE* fOut = nullptr;

    if (fIn) {
        fOut = fopen(outC, "w");
        if (fOut) {
            // 1. Check Header (Lua 5.3 Signature)
            unsigned char header[4];
            if (fread(header, 1, 4, fIn) == 4) {
                if (header[0] == 0x1B && header[1] == 'L' && header[2] == 'u' && header[3] == 'a') {
                    fprintf(fOut, ".header\n; Lua Binary Chunk\n");

                    // Skip Version, Format, Data, Int, SizeT, Instruction, Integer, Number (approx 30 bytes for 64-bit)
                    fseek(fIn, 30, SEEK_CUR); // Skipping header for this lite parser

                    uint32_t instruction;
                    int pc = 0;
                    while (fread(&instruction, sizeof(uint32_t), 1, fIn) == 1) {
                        int op = GET_OPCODE(instruction);
                        int a = GETARG_A(instruction);

                        // Safe bounds check. Array has 47 names + NULL. Valid ops are 0..46.
                        if (op >= 0 && op < 47 && lua_opnames[op] != NULL) {
                            fprintf(fOut, "[%04d] %-10s %d", pc, lua_opnames[op], a);

                            int b = GETARG_B(instruction);
                            int c = GETARG_C(instruction);
                            fprintf(fOut, " %d %d\n", b, c);
                        } else {
                            fprintf(fOut, "[%04d] UNKNOWN_OP %d\n", pc, op);
                        }
                        pc++;
                    }
                } else {
                    fprintf(fOut, "; Error: Invalid Lua Signature\n");
                }
            } else {
                fprintf(fOut, "; Error: Failed to read header\n");
            }
            fclose(fOut);
        }
        fclose(fIn);
    }

    env->ReleaseStringUTFChars(inPath, inC);
    env->ReleaseStringUTFChars(outPath, outC);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_techted89_gameex_NativeScanner_assembleScript(
        JNIEnv* env,
        jobject,
        jstring inPath,
        jstring outPath) {

    if (inPath == nullptr || outPath == nullptr) return;

    // Stub implementation: "Assemble" by creating a fake .lua binary
    const char* inC = env->GetStringUTFChars(inPath, nullptr);
    const char* outC = env->GetStringUTFChars(outPath, nullptr);

    if (inC == nullptr || outC == nullptr) {
        if (inC) env->ReleaseStringUTFChars(inPath, inC);
        if (outC) env->ReleaseStringUTFChars(outPath, outC);
        return;
    }

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "Assembling %s to %s", inC, outC);

    FILE* f = fopen(outC, "wb");
    if (f) {
        // Lua 5.2 Signature: Esc Lua
        const unsigned char header[] = {0x1B, 0x4C, 0x75, 0x61, 0x52, 0x00, 0x01, 0x04};
        fwrite(header, 1, sizeof(header), f);
        fclose(f);
    }

    env->ReleaseStringUTFChars(inPath, inC);
    env->ReleaseStringUTFChars(outPath, outC);
}
