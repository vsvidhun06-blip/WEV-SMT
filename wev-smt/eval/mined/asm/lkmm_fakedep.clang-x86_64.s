	.text
	.file	"lkmm_fakedep.c"
	.globl	lb_xor                          # -- Begin function lb_xor
	.p2align	4, 0x90
	.type	lb_xor,@function
lb_xor:                                 # @lb_xor
# %bb.0:
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	retq
.Lfunc_end0:
	.size	lb_xor, .Lfunc_end0-lb_xor
                                        # -- End function
	.globl	lb_linear                       # -- Begin function lb_linear
	.p2align	4, 0x90
	.type	lb_linear,@function
lb_linear:                              # @lb_linear
# %bb.0:
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	retq
.Lfunc_end1:
	.size	lb_linear, .Lfunc_end1-lb_linear
                                        # -- End function
	.globl	lb_sub                          # -- Begin function lb_sub
	.p2align	4, 0x90
	.type	lb_sub,@function
lb_sub:                                 # @lb_sub
# %bb.0:
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	retq
.Lfunc_end2:
	.size	lb_sub, .Lfunc_end2-lb_sub
                                        # -- End function
	.globl	lb_andzero                      # -- Begin function lb_andzero
	.p2align	4, 0x90
	.type	lb_andzero,@function
lb_andzero:                             # @lb_andzero
# %bb.0:
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	retq
.Lfunc_end3:
	.size	lb_andzero, .Lfunc_end3-lb_andzero
                                        # -- End function
	.globl	lb_mulzero                      # -- Begin function lb_mulzero
	.p2align	4, 0x90
	.type	lb_mulzero,@function
lb_mulzero:                             # @lb_mulzero
# %bb.0:
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	retq
.Lfunc_end4:
	.size	lb_mulzero, .Lfunc_end4-lb_mulzero
                                        # -- End function
	.globl	lb_selfand                      # -- Begin function lb_selfand
	.p2align	4, 0x90
	.type	lb_selfand,@function
lb_selfand:                             # @lb_selfand
# %bb.0:
	movl	(%rdi), %eax
	movl	%eax, (%rsi)
	retq
.Lfunc_end5:
	.size	lb_selfand, .Lfunc_end5-lb_selfand
                                        # -- End function
	.globl	lb_copy                         # -- Begin function lb_copy
	.p2align	4, 0x90
	.type	lb_copy,@function
lb_copy:                                # @lb_copy
# %bb.0:
	movl	(%rdi), %eax
	movl	%eax, (%rsi)
	retq
.Lfunc_end6:
	.size	lb_copy, .Lfunc_end6-lb_copy
                                        # -- End function
	.globl	lb_orcompl                      # -- Begin function lb_orcompl
	.p2align	4, 0x90
	.type	lb_orcompl,@function
lb_orcompl:                             # @lb_orcompl
# %bb.0:
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	retq
.Lfunc_end7:
	.size	lb_orcompl, .Lfunc_end7-lb_orcompl
                                        # -- End function
	.globl	lb_fakectrl                     # -- Begin function lb_fakectrl
	.p2align	4, 0x90
	.type	lb_fakectrl,@function
lb_fakectrl:                            # @lb_fakectrl
# %bb.0:
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	retq
.Lfunc_end8:
	.size	lb_fakectrl, .Lfunc_end8-lb_fakectrl
                                        # -- End function
	.globl	lb_realctrl                     # -- Begin function lb_realctrl
	.p2align	4, 0x90
	.type	lb_realctrl,@function
lb_realctrl:                            # @lb_realctrl
# %bb.0:
	movl	(%rdi), %eax
	xorl	%ecx, %ecx
	cmpl	$1, %eax
	setne	%cl
	incl	%ecx
	movl	%ecx, (%rsi)
	retq
.Lfunc_end9:
	.size	lb_realctrl, .Lfunc_end9-lb_realctrl
                                        # -- End function
	.globl	lb_shiftmask                    # -- Begin function lb_shiftmask
	.p2align	4, 0x90
	.type	lb_shiftmask,@function
lb_shiftmask:                           # @lb_shiftmask
# %bb.0:
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	retq
.Lfunc_end10:
	.size	lb_shiftmask, .Lfunc_end10-lb_shiftmask
                                        # -- End function
	.globl	lb_modone                       # -- Begin function lb_modone
	.p2align	4, 0x90
	.type	lb_modone,@function
lb_modone:                              # @lb_modone
# %bb.0:
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	retq
.Lfunc_end11:
	.size	lb_modone, .Lfunc_end11-lb_modone
                                        # -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
