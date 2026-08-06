	.text
	.file	"locks.c"
	.globl	dekker_p0                       # -- Begin function dekker_p0
	.p2align	4, 0x90
	.type	dekker_p0,@function
dekker_p0:                              # @dekker_p0
# %bb.0:
	movl	$1, (%rdi)
	movl	4(%rdi), %ecx
	xorl	%eax, %eax
	testl	%ecx, %ecx
	je	.LBB0_1
# %bb.2:
	retq
.LBB0_1:
	movl	$1, 12(%rdi)
	movl	$1, %eax
	retq
.Lfunc_end0:
	.size	dekker_p0, .Lfunc_end0-dekker_p0
                                        # -- End function
	.globl	peterson_p0                     # -- Begin function peterson_p0
	.p2align	4, 0x90
	.type	peterson_p0,@function
peterson_p0:                            # @peterson_p0
# %bb.0:
	movl	$1, (%rdi)
	movl	$0, 8(%rdi)
	movl	4(%rdi), %eax
	movl	8(%rdi), %ecx
	testl	%eax, %eax
	je	.LBB1_2
# %bb.1:
	xorl	%eax, %eax
	testl	%ecx, %ecx
	jne	.LBB1_2
# %bb.3:
	retq
.LBB1_2:
	movl	$1, 12(%rdi)
	movl	$1, %eax
	retq
.Lfunc_end1:
	.size	peterson_p0, .Lfunc_end1-peterson_p0
                                        # -- End function
	.globl	ticket_lock                     # -- Begin function ticket_lock
	.p2align	4, 0x90
	.type	ticket_lock,@function
ticket_lock:                            # @ticket_lock
# %bb.0:
	movl	$1, %eax
	lock		xaddl	%eax, (%rdi)
	.p2align	4, 0x90
.LBB2_1:                                # =>This Inner Loop Header: Depth=1
	movl	4(%rdi), %ecx
	cmpl	%eax, %ecx
	jne	.LBB2_1
# %bb.2:
	retq
.Lfunc_end2:
	.size	ticket_lock, .Lfunc_end2-ticket_lock
                                        # -- End function
	.globl	ticket_unlock                   # -- Begin function ticket_unlock
	.p2align	4, 0x90
	.type	ticket_unlock,@function
ticket_unlock:                          # @ticket_unlock
# %bb.0:
	incl	4(%rdi)
	retq
.Lfunc_end3:
	.size	ticket_unlock, .Lfunc_end3-ticket_unlock
                                        # -- End function
	.globl	ticket_waiters                  # -- Begin function ticket_waiters
	.p2align	4, 0x90
	.type	ticket_waiters,@function
ticket_waiters:                         # @ticket_waiters
# %bb.0:
	movl	(%rdi), %eax
	movl	4(%rdi), %ecx
	subl	%ecx, %eax
	retq
.Lfunc_end4:
	.size	ticket_waiters, .Lfunc_end4-ticket_waiters
                                        # -- End function
	.globl	mcs_acquire                     # -- Begin function mcs_acquire
	.p2align	4, 0x90
	.type	mcs_acquire,@function
mcs_acquire:                            # @mcs_acquire
# %bb.0:
	movq	$0, (%rsi)
	movq	%rsi, %rax
	xchgq	%rax, (%rdi)
	testq	%rax, %rax
	je	.LBB5_3
# %bb.1:
	movl	$1, 8(%rsi)
	movq	%rsi, (%rax)
	.p2align	4, 0x90
.LBB5_2:                                # =>This Inner Loop Header: Depth=1
	movl	8(%rsi), %eax
	testl	%eax, %eax
	jne	.LBB5_2
.LBB5_3:
	retq
.Lfunc_end5:
	.size	mcs_acquire, .Lfunc_end5-mcs_acquire
                                        # -- End function
	.globl	mcs_release                     # -- Begin function mcs_release
	.p2align	4, 0x90
	.type	mcs_release,@function
mcs_release:                            # @mcs_release
# %bb.0:
	movq	(%rsi), %rax
	testq	%rax, %rax
	je	.LBB6_2
# %bb.1:
	movl	$0, 8(%rax)
.LBB6_2:
	retq
.Lfunc_end6:
	.size	mcs_release, .Lfunc_end6-mcs_release
                                        # -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
