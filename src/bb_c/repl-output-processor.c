#include <stdio.h>
#include <stdlib.h>

// gcc -o -Wall ./src/bb_c/repl-output-processor.c -o repl-output-processor; chmod u+x ./repl-output-processor

const char char_eval_end = 0;

// buffer ---------------------------------------------------------------------------------------

#define buffer_size 200

char buffer[buffer_size];
int bi = 0;
int esc_seq = 0;

void clear_buffer(void) {
  bi = 0;
  buffer[0] = 0;
}

void add_to_buffer(char c) {
  if (0x1B == c) // (27 == c) escape
    esc_seq = 1;
  else if (esc_seq && 0x40 <= c && c <= 0x7E && c != '[') // Control Sequence Introducer "final byte"
    esc_seq = 0;
  else if (!esc_seq && c != 8) { // not \backspace
    if (bi < buffer_size - 1) {
      buffer[bi++] = c;
      buffer[bi] = 0;
    } else
      bi++;
  }
}

// template ---------------------------------------------------------------------------------------

char* template = ",(load-file \".repl-input\")";
int ti = 0;


int main(int argc, char **argv) {

  setbuf(stdin, NULL);

  char c;

  char *filename = ".repl-result";
  FILE *fp;
  int write_to_file = 0;

  while ((c = getc(stdin)) != EOF) {

    if (c == template[ti]) {
      ti++;
    } else {
      if (0 == template[ti]) write_to_file = 1;
      else for (int i=0; i<ti; i++) putc(template[i], stdout);
      ti = 0;
      putc(c, stdout);
    }

    if (write_to_file) add_to_buffer(c);

    if (c == char_eval_end && write_to_file) {
      if((fp = fopen(filename, "w")) != NULL) {
        fputs(buffer, fp);
        if (bi > buffer_size + 2) fputs("...", fp);
        fclose(fp);
      }
      clear_buffer();
      write_to_file = 0;
    }

  }

  return 0;
}