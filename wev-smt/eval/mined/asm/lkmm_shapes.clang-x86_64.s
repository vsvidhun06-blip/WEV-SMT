	.text
	.file	"lkmm_shapes.c"
	.globl	mp_writer                       # -- Begin function mp_writer
	.p2align	4, 0x90
	.type	mp_writer,@function
mp_writer:                              # @mp_writer
# %bb.0:
	movl	$42, (%rdi)
	movl	$1, (%rsi)
	retq
.Lfunc_end0:
	.size	mp_writer, .Lfunc_end0-mp_writer
                                        # -- End function
	.globl	mp_reader                       # -- Begin function mp_reader
	.p2align	4, 0x90
	.type	mp_reader,@function
mp_reader:                              # @mp_reader
# %bb.0:
	movl	(%rsi), %eax
	testl	%eax, %eax
	je	.LBB1_1
# %bb.2:
	movl	(%rdi), %eax
	retq
.LBB1_1:
	movl	$-1, %eax
	retq
.Lfunc_end1:
	.size	mp_reader, .Lfunc_end1-mp_reader
                                        # -- End function
	.globl	lb_thread_data                  # -- Begin function lb_thread_data
	.p2align	4, 0x90
	.type	lb_thread_data,@function
lb_thread_data:                         # @lb_thread_data
# %bb.0:
	movl	(%rdi), %eax
	movl	%eax, (%rsi)
	retq
.Lfunc_end2:
	.size	lb_thread_data, .Lfunc_end2-lb_thread_data
                                        # -- End function
	.globl	lb_thread_ctrl                  # -- Begin function lb_thread_ctrl
	.p2align	4, 0x90
	.type	lb_thread_ctrl,@function
lb_thread_ctrl:                         # @lb_thread_ctrl
# %bb.0:
	movl	(%rdi), %eax
	testl	%eax, %eax
	je	.LBB3_2
# %bb.1:
	movl	$1, (%rsi)
.LBB3_2:
	retq
.Lfunc_end3:
	.size	lb_thread_ctrl, .Lfunc_end3-lb_thread_ctrl
                                        # -- End function
	.globl	lb_thread_addr                  # -- Begin function lb_thread_addr
	.p2align	4, 0x90
	.type	lb_thread_addr,@function
lb_thread_addr:                         # @lb_thread_addr
# %bb.0:
	movq	(%rdi), %rax
	testq	%rax, %rax
	cmovneq	%rax, %rsi
	movl	(%rsi), %eax
	retq
.Lfunc_end4:
	.size	lb_thread_addr, .Lfunc_end4-lb_thread_addr
                                        # -- End function
	.globl	sb_thread                       # -- Begin function sb_thread
	.p2align	4, 0x90
	.type	sb_thread,@function
sb_thread:                              # @sb_thread
# %bb.0:
	movl	$1, (%rdi)
	movl	(%rsi), %eax
	retq
.Lfunc_end5:
	.size	sb_thread, .Lfunc_end5-sb_thread
                                        # -- End function
	.globl	wrc_p1                          # -- Begin function wrc_p1
	.p2align	4, 0x90
	.type	wrc_p1,@function
wrc_p1:                                 # @wrc_p1
# %bb.0:
	movl	(%rdi), %eax
	movl	%eax, (%rsi)
	retq
.Lfunc_end6:
	.size	wrc_p1, .Lfunc_end6-wrc_p1
                                        # -- End function
	.globl	isa2_p1                         # -- Begin function isa2_p1
	.p2align	4, 0x90
	.type	isa2_p1,@function
isa2_p1:                                # @isa2_p1
# %bb.0:
	movl	(%rdi), %eax
	movl	%eax, (%rsi)
	retq
.Lfunc_end7:
	.size	isa2_p1, .Lfunc_end7-isa2_p1
                                        # -- End function
	.globl	r_p0                            # -- Begin function r_p0
	.p2align	4, 0x90
	.type	r_p0,@function
r_p0:                                   # @r_p0
# %bb.0:
	movl	$1, (%rdi)
	movl	$1, (%rsi)
	retq
.Lfunc_end8:
	.size	r_p0, .Lfunc_end8-r_p0
                                        # -- End function
	.globl	s_p1                            # -- Begin function s_p1
	.p2align	4, 0x90
	.type	s_p1,@function
s_p1:                                   # @s_p1
# %bb.0:
	movl	(%rdi), %eax
	incl	%eax
	movl	%eax, (%rsi)
	retq
.Lfunc_end9:
	.size	s_p1, .Lfunc_end9-s_p1
                                        # -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
