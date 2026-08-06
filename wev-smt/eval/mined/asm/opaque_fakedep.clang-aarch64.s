	.text
	.file	"opaque_fakedep.c"
	.globl	ofd_volatile_xor                // -- Begin function ofd_volatile_xor
	.p2align	2
	.type	ofd_volatile_xor,@function
ofd_volatile_xor:                       // @ofd_volatile_xor
// %bb.0:
	mov	w8, #1                          // =0x1
	ldr	wzr, [x0]
	ldr	wzr, [x0]
	str	w8, [x1]
	ret
.Lfunc_end0:
	.size	ofd_volatile_xor, .Lfunc_end0-ofd_volatile_xor
                                        // -- End function
	.globl	ofd_hidden_sub                  // -- Begin function ofd_hidden_sub
	.p2align	2
	.type	ofd_hidden_sub,@function
ofd_hidden_sub:                         // @ofd_hidden_sub
// %bb.0:
	ldr	w8, [x0]
	mov	w9, w8
	//APP
	//NO_APP
	sub	w8, w8, w9
	add	w8, w8, #1
	str	w8, [x1]
	ret
.Lfunc_end1:
	.size	ofd_hidden_sub, .Lfunc_end1-ofd_hidden_sub
                                        // -- End function
	.globl	ofd_hidden_andzero              // -- Begin function ofd_hidden_andzero
	.p2align	2
	.type	ofd_hidden_andzero,@function
ofd_hidden_andzero:                     // @ofd_hidden_andzero
// %bb.0:
	ldr	w8, [x0]
	//APP
	//NO_APP
	and	w8, w2, w8
	add	w8, w8, #1
	str	w8, [x1]
	ret
.Lfunc_end2:
	.size	ofd_hidden_andzero, .Lfunc_end2-ofd_hidden_andzero
                                        // -- End function
	.globl	ofd_opaque_cancel               // -- Begin function ofd_opaque_cancel
	.p2align	2
	.type	ofd_opaque_cancel,@function
ofd_opaque_cancel:                      // @ofd_opaque_cancel
// %bb.0:
	stp	x30, x21, [sp, #-32]!           // 16-byte Folded Spill
	stp	x20, x19, [sp, #16]             // 16-byte Folded Spill
	mov	x20, x1
	ldr	w19, [x0]
	mov	w0, w19
	bl	opaque_identity
	mov	w21, w0
	mov	w0, w19
	bl	opaque_identity
	sub	w8, w21, w0
	add	w8, w8, #1
	str	w8, [x20]
	ldp	x20, x19, [sp, #16]             // 16-byte Folded Reload
	ldp	x30, x21, [sp], #32             // 16-byte Folded Reload
	ret
.Lfunc_end3:
	.size	ofd_opaque_cancel, .Lfunc_end3-ofd_opaque_cancel
                                        // -- End function
	.globl	ofd_hidden_xor                  // -- Begin function ofd_hidden_xor
	.p2align	2
	.type	ofd_hidden_xor,@function
ofd_hidden_xor:                         // @ofd_hidden_xor
// %bb.0:
	ldr	w8, [x0]
	mov	w9, w8
	//APP
	//NO_APP
	//APP
	//NO_APP
	eor	w8, w8, w9
	orr	w8, w8, #0x1
	str	w8, [x1]
	ret
.Lfunc_end4:
	.size	ofd_hidden_xor, .Lfunc_end4-ofd_hidden_xor
                                        // -- End function
	.globl	ofd_hidden_real                 // -- Begin function ofd_hidden_real
	.p2align	2
	.type	ofd_hidden_real,@function
ofd_hidden_real:                        // @ofd_hidden_real
// %bb.0:
	ldr	w8, [x0]
	//APP
	//NO_APP
	add	w8, w8, #1
	str	w8, [x1]
	ret
.Lfunc_end5:
	.size	ofd_hidden_real, .Lfunc_end5-ofd_hidden_real
                                        // -- End function
	.ident	"Ubuntu clang version 18.1.3 (1ubuntu1)"
	.section	".note.GNU-stack","",@progbits
	.addrsig
