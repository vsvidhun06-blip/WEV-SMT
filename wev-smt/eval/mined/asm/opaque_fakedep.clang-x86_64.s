	.text
	.file	"opaque_fakedep.c"
	.globl	ofd_volatile_xor                # -- Begin function ofd_volatile_xor
	.p2align	4, 0x90
	.type	ofd_volatile_xor,@function
ofd_volatile_xor:                       # @ofd_volatile_xor
# %bb.0:
	movl	(%rdi), %eax
	movl	(%rdi), %eax
	movl	$1, (%rsi)
	retq
.Lfunc_end0:
	.size	ofd_volatile_xor, .Lfunc_end0-ofd_volatile_xor
                                        # -- End function
	.globl	ofd_hidden_sub                  # -- Begin function ofd_hidden_sub
	.p2align	4, 0x90
	.type	ofd_hidden_sub,@function
ofd_hidden_sub:                         # @ofd_hidden_sub
# %bb.0:
	movl	(%rdi), %eax
	movl	%eax, %ecx
	#APP
	#NO_APP
	subl	%ecx, %eax
	incl	%eax
	movl	%eax, (%rsi)
	retq
.Lfunc_end1:
	.size	ofd_hidden_sub, .Lfunc_end1-ofd_hidden_sub
                                        # -- End function
	.globl	ofd_hidden_andzero              # -- Begin function ofd_hidden_andzero
	.p2align	4, 0x90
	.type	ofd_hidden_andzero,@function
ofd_hidden_andzero:                     # @ofd_hidden_andzero
# %bb.0:
	movl	(%rdi), %eax
	#APP
	#NO_APP
	andl	%eax, %edx
	incl	%edx
	movl	%edx, (%rsi)
	retq
.Lfunc_end2:
	.size	ofd_hidden_andzero, .Lfunc_end2-ofd_hidden_andzero
                                        # -- End function
	.globl	ofd_opaque_cancel               # -- Begin function ofd_opaque_cancel
	.p2align	4, 0x90
	.type	ofd_opaque_cancel,@function
ofd_opaque_cancel:                      # @ofd_opaque_cancel
# %bb.0:
	pushq	%rbp
	pushq	%r14
	pushq	%rbx
	movq	%rsi, %rbx
	movl	(%rdi), %ebp
	movl	%ebp, %edi
	callq	opaque_identity@PLT
	movl	%eax, %r14d
	movl	%ebp, %edi
	callq	opaque_identity@PLT
	subl	%eax, %r14d
	incl	%r14d
	movl	%r14d, (%rbx)
	popq	%rbx
	popq	%r14
	popq	%rbp
	retq
.Lfunc_end3:
	.size	ofd_opaque_cancel, .Lfunc_end3-ofd_opaque_cancel
                                        # -- End function
	.globl	ofd_hidden_xor                  # -- Begin function ofd_hidden_xor
	.p2align	4, 0x90
	.type	ofd_hidden_xor,@function
ofd_hidden_xor:                         # @ofd_hidden_xor
# %bb.0:
	movl	(%rdi), %eax
	movl	%eax, %ecx
	#APP
	#NO_APP
	#APP
	#NO_APP
	xorl	%ecx, %eax
	orl	$1, %eax
	movl	%eax, (%rsi)
	retq
.Lfunc_end4:
	.size	ofd_hidden_xor, .Lfunc_end4-ofd_hidden_xor
                                        # -- End function
	.globl	ofd_hidden_real                 # -- Begin function ofd_hidden_real
	.p2align	4, 0x90
	.type	ofd_hidden_real,@function
ofd_hidden_real:                        # @ofd_hidden_real
# %bb.0:
	movl	(%rdi), %eax
	#APP
	#NO_APP
	incl	%eax
	movl	%eax, (%rsi)
	retq
.Lfunc_end5:
	.size	ofd_hidden_real, .Lfunc_end5-ofd_hidden_real
                                        # -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
