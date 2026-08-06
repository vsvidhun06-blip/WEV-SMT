	.text
	.file	"lkmm_shapes.c"
	.globl	mp_writer                       // -- Begin function mp_writer
	.p2align	2
	.type	mp_writer,@function
mp_writer:                              // @mp_writer
// %bb.0:
	mov	w8, #42                         // =0x2a
	mov	w9, #1                          // =0x1
	str	w8, [x0]
	stlr	w9, [x1]
	ret
.Lfunc_end0:
	.size	mp_writer, .Lfunc_end0-mp_writer
                                        // -- End function
	.globl	mp_reader                       // -- Begin function mp_reader
	.p2align	2
	.type	mp_reader,@function
mp_reader:                              // @mp_reader
// %bb.0:
	ldar	w8, [x1]
	cbz	w8, .LBB1_2
// %bb.1:
	ldr	w0, [x0]
	ret
.LBB1_2:
	mov	w0, #-1                         // =0xffffffff
	ret
.Lfunc_end1:
	.size	mp_reader, .Lfunc_end1-mp_reader
                                        // -- End function
	.globl	lb_thread_data                  // -- Begin function lb_thread_data
	.p2align	2
	.type	lb_thread_data,@function
lb_thread_data:                         // @lb_thread_data
// %bb.0:
	ldr	w8, [x0]
	str	w8, [x1]
	ret
.Lfunc_end2:
	.size	lb_thread_data, .Lfunc_end2-lb_thread_data
                                        // -- End function
	.globl	lb_thread_ctrl                  // -- Begin function lb_thread_ctrl
	.p2align	2
	.type	lb_thread_ctrl,@function
lb_thread_ctrl:                         // @lb_thread_ctrl
// %bb.0:
	ldr	w8, [x0]
	cbz	w8, .LBB3_2
// %bb.1:
	mov	w8, #1                          // =0x1
	str	w8, [x1]
.LBB3_2:
	ret
.Lfunc_end3:
	.size	lb_thread_ctrl, .Lfunc_end3-lb_thread_ctrl
                                        // -- End function
	.globl	lb_thread_addr                  // -- Begin function lb_thread_addr
	.p2align	2
	.type	lb_thread_addr,@function
lb_thread_addr:                         // @lb_thread_addr
// %bb.0:
	ldr	x8, [x0]
	cmp	x8, #0
	csel	x8, x1, x8, eq
	ldr	w0, [x8]
	ret
.Lfunc_end4:
	.size	lb_thread_addr, .Lfunc_end4-lb_thread_addr
                                        // -- End function
	.globl	sb_thread                       // -- Begin function sb_thread
	.p2align	2
	.type	sb_thread,@function
sb_thread:                              // @sb_thread
// %bb.0:
	mov	w8, #1                          // =0x1
	str	w8, [x0]
	ldr	w0, [x1]
	ret
.Lfunc_end5:
	.size	sb_thread, .Lfunc_end5-sb_thread
                                        // -- End function
	.globl	wrc_p1                          // -- Begin function wrc_p1
	.p2align	2
	.type	wrc_p1,@function
wrc_p1:                                 // @wrc_p1
// %bb.0:
	ldr	w8, [x0]
	stlr	w8, [x1]
	ret
.Lfunc_end6:
	.size	wrc_p1, .Lfunc_end6-wrc_p1
                                        // -- End function
	.globl	isa2_p1                         // -- Begin function isa2_p1
	.p2align	2
	.type	isa2_p1,@function
isa2_p1:                                // @isa2_p1
// %bb.0:
	ldar	w8, [x0]
	stlr	w8, [x1]
	ret
.Lfunc_end7:
	.size	isa2_p1, .Lfunc_end7-isa2_p1
                                        // -- End function
	.globl	r_p0                            // -- Begin function r_p0
	.p2align	2
	.type	r_p0,@function
r_p0:                                   // @r_p0
// %bb.0:
	mov	w8, #1                          // =0x1
	str	w8, [x0]
	stlr	w8, [x1]
	ret
.Lfunc_end8:
	.size	r_p0, .Lfunc_end8-r_p0
                                        // -- End function
	.globl	s_p1                            // -- Begin function s_p1
	.p2align	2
	.type	s_p1,@function
s_p1:                                   // @s_p1
// %bb.0:
	ldr	w8, [x0]
	add	w8, w8, #1
	str	w8, [x1]
	ret
.Lfunc_end9:
	.size	s_p1, .Lfunc_end9-s_p1
                                        // -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
