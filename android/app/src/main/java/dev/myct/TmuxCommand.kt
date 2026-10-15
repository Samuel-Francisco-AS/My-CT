package dev.myct

// Fixed command, no parameters from the network or user input, and no trailing newline.
// The app displays/copies it; the user chooses where to paste and presses Enter.
const val MYCT_TMUX_COMMAND = "tmux -L myct -f /dev/null new-session -A -s myct-main"
