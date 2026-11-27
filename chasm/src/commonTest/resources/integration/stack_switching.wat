(module
  (type $ft_void (func))
  (type $ft_i32_to_i32 (func (param i32) (result i32)))
  (type $ft_i32_i32_to_i32 (func (param i32 i32) (result i32)))
  (type $ft_i32_i32_i32_to_i32 (func (param i32 i32 i32) (result i32)))

  (type $ct_void (cont $ft_void))
  (type $ct_i32_to_i32 (cont $ft_i32_to_i32))
  (type $ct_i32_i32_to_i32 (cont $ft_i32_i32_to_i32))
  (type $ct_i32_i32_i32_to_i32 (cont $ft_i32_i32_i32_to_i32))

  ;; Covariant continuation subtyping: $sub_fn <: $sup_fn => $sub_ct <: $sup_ct
  (type $box (struct (field i32)))
  (type $sup_fn (sub (func (param (ref null $box)) (result anyref))))
  (type $sub_fn (sub $sup_fn (func (param anyref) (result (ref $box)))))
  (type $sup_ct (sub (cont $sup_fn)))
  (type $sub_ct (sub $sup_ct (cont $sub_fn)))

  ;; Switch continuation types
  (rec
    (type $sw_fn (func (param i32 (ref null $sw_ct)) (result i32)))
    (type $sw_ct (cont $sw_fn))
  )

  (import "env" "gc" (func $host_gc))
  (import "env" "add_host" (func $add_host (param i32 i32) (result i32)))

  (tag $yield (param i32) (result i32))
  (tag $outer (param i32) (result i32))
  (tag $inner (param i32) (result i32))
  (tag $err (param i32))
  (tag $sw_tag (result i32))

  (elem declare func
    $add_one
    $sum3
    $generator_body
    $nested_body
    $catch_body
    $uncaught_body
    $gc_body
    $sub_fn_impl
    $peer_a
    $peer_b
    $add_host
  )

  (func $add_one (type $ft_i32_to_i32) (param $x i32) (result i32)
    (i32.add (local.get $x) (i32.const 1))
  )

  (func $sum3 (type $ft_i32_i32_i32_to_i32) (param $a i32) (param $b i32) (param $c i32) (result i32)
    (i32.add
      (i32.mul (local.get $a) (i32.const 100))
      (i32.add (i32.mul (local.get $b) (i32.const 10)) (local.get $c)))
  )

  ;; 1. Basic cont.new + resume
  (func (export "basic_resume") (param $x i32) (result i32)
    (resume $ct_i32_to_i32
      (local.get $x)
      (cont.new $ct_i32_to_i32 (ref.func $add_one)))
  )

  ;; 2. Chained cont.bind (3 -> 2 -> 1 -> 0)
  (func (export "chained_bind") (result i32)
    (local $c3 (ref $ct_i32_i32_i32_to_i32))
    (local $c2 (ref $ct_i32_i32_to_i32))
    (local $c1 (ref $ct_i32_to_i32))
    (local.set $c3 (cont.new $ct_i32_i32_i32_to_i32 (ref.func $sum3)))
    (local.set $c2 (cont.bind $ct_i32_i32_i32_to_i32 $ct_i32_i32_to_i32 (i32.const 1) (local.get $c3)))
    (local.set $c1 (cont.bind $ct_i32_i32_to_i32 $ct_i32_to_i32 (i32.const 2) (local.get $c2)))
    (resume $ct_i32_to_i32 (i32.const 3) (local.get $c1))
  )

  ;; 3. Multi-step suspend/resume with values flowing both ways
  (func $generator_body (type $ft_i32_to_i32) (param $x i32) (result i32)
    (local $y i32)
    (local $z i32)
    (local.set $y (suspend $yield (i32.add (local.get $x) (i32.const 10))))
    (local.set $z (suspend $yield (i32.add (local.get $y) (i32.const 20))))
    (i32.add (local.get $z) (i32.const 30))
  )

  (func (export "suspend_roundtrip") (param $start i32) (result i32)
    (local $k (ref null $ct_i32_to_i32))
    (local $v i32)
    (local.set $k (cont.new $ct_i32_to_i32 (ref.func $generator_body)))
    (local.set $v (local.get $start))
    (loop $step (result i32)
      (block $on_yield (result i32 (ref $ct_i32_to_i32))
        (return (resume $ct_i32_to_i32 (on $yield $on_yield)
          (local.get $v)
          (local.get $k)))
      )
      (local.set $k)
      (local.set $v (i32.mul (i32.const 2)))
      (br $step)
    )
  )

  ;; 4. Nested prompts: suspending to outer captures inner prompt and restores it on resume
  (func $nested_body (type $ft_i32_to_i32) (param $x i32) (result i32)
    (local $a i32)
    (local $b i32)
    (local.set $a (suspend $outer (local.get $x)))
    (local.set $b (suspend $inner (local.get $a)))
    (i32.add (local.get $a) (local.get $b))
  )

  (func $inner_runner (type $ft_i32_to_i32) (param $x i32) (result i32)
    (local $k (ref $ct_i32_to_i32))
    (local $v i32)
    (block $on_inner (result i32 (ref $ct_i32_to_i32))
      (return (resume $ct_i32_to_i32 (on $inner $on_inner)
        (local.get $x)
        (cont.new $ct_i32_to_i32 (ref.func $nested_body))))
    )
    (local.set $k)
    (local.set $v)
    (resume $ct_i32_to_i32 (i32.add (local.get $v) (i32.const 5)) (local.get $k))
  )

  (elem declare func $inner_runner)

  (func (export "nested_prompts") (param $x i32) (result i32)
    (local $k (ref $ct_i32_to_i32))
    (local $v i32)
    (block $on_outer (result i32 (ref $ct_i32_to_i32))
      (return (resume $ct_i32_to_i32 (on $outer $on_outer)
        (local.get $x)
        (cont.new $ct_i32_to_i32 (ref.func $inner_runner))))
    )
    (local.set $k)
    (local.set $v)
    (resume $ct_i32_to_i32 (i32.mul (local.get $v) (i32.const 10)) (local.get $k))
  )

  ;; 5. resume_throw caught inside suspended continuation
  (func $catch_body (type $ft_i32_to_i32) (param $x i32) (result i32)
    (block $caught (result i32)
      (try_table (result i32) (catch $err $caught)
        (return (suspend $yield (local.get $x)))
      )
      (return)
    )
    (i32.add (i32.const 1000))
  )

  (func (export "resume_throw_caught") (result i32)
    (local $k (ref $ct_i32_to_i32))
    (block $on_yield (result i32 (ref $ct_i32_to_i32))
      (return (resume $ct_i32_to_i32 (on $yield $on_yield)
        (i32.const 7)
        (cont.new $ct_i32_to_i32 (ref.func $catch_body))))
    )
    (local.set $k)
    (drop)
    (resume_throw $ct_i32_to_i32 $err (i32.const 42) (local.get $k))
  )

  ;; 6. resume_throw uncaught propagates to caller's try_table
  (func $uncaught_body (type $ft_i32_to_i32) (param $x i32) (result i32)
    (suspend $yield (local.get $x))
  )

  (func (export "resume_throw_uncaught") (result i32)
    (local $k (ref $ct_i32_to_i32))
    (block $on_yield (result i32 (ref $ct_i32_to_i32))
      (return (resume $ct_i32_to_i32 (on $yield $on_yield)
        (i32.const 1)
        (cont.new $ct_i32_to_i32 (ref.func $uncaught_body))))
    )
    (local.set $k)
    (drop)
    (block $caught (result i32)
      (try_table (result i32) (catch $err $caught)
        (return (resume_throw $ct_i32_to_i32 $err (i32.const 99) (local.get $k)))
      )
      (return)
    )
  )

  ;; 7. resume_throw_ref rethrows captured exnref into continuation
  (func (export "resume_throw_ref_caught") (result i32)
    (local $k (ref $ct_i32_to_i32))
    (local $exn exnref)
    (block $on_yield (result i32 (ref $ct_i32_to_i32))
      (return (resume $ct_i32_to_i32 (on $yield $on_yield)
        (i32.const 1)
        (cont.new $ct_i32_to_i32 (ref.func $catch_body))))
    )
    (local.set $k)
    (drop)
    (block $got_exn (result i32 exnref)
      (try_table (catch_ref $err $got_exn)
        (throw $err (i32.const 77))
      )
      (unreachable)
    )
    (local.set $exn)
    (drop)
    (resume_throw_ref $ct_i32_to_i32 (local.get $exn) (local.get $k))
  )

  ;; 8. Direct coroutine switch via (on $sw_tag switch) + switch
  (func $peer_a (type $sw_fn) (param $v i32) (param $other (ref null $sw_ct)) (result i32)
    (local $next_v i32)
    (local $k (ref null $sw_ct))
    ;; Switch to peer_b with v + 1, receive (v', peer_b_cont) back
    (switch $sw_ct $sw_tag (i32.add (local.get $v) (i32.const 1)) (local.get $other))
    (local.set $k)
    (local.set $next_v)
    ;; Switch back to peer_b with next_v + 10; peer_b will return directly to resume
    (switch $sw_ct $sw_tag (i32.add (local.get $next_v) (i32.const 10)) (local.get $k))
    (drop)
  )

  (func $peer_b (type $sw_fn) (param $v i32) (param $other (ref null $sw_ct)) (result i32)
    (local $next_v i32)
    (local $k (ref null $sw_ct))
    ;; Switch back to peer_a with v * 2
    (switch $sw_ct $sw_tag (i32.mul (local.get $v) (i32.const 2)) (local.get $other))
    (local.set $k)
    (local.set $next_v)
    ;; Return final value to enclosing resume prompt
    (i32.add (local.get $next_v) (i32.const 100))
  )

  (func (export "switch_ping_pong") (param $start i32) (result i32)
    ;; start=5 -> peer_a sends 6 -> peer_b sends 12 -> peer_a sends 22 -> peer_b returns 122
    (resume $sw_ct (on $sw_tag switch)
      (local.get $start)
      (cont.new $sw_ct (ref.func $peer_b))
      (cont.new $sw_ct (ref.func $peer_a)))
  )

  ;; 9. GC root tracing inside suspended continuation stack
  (func $gc_body (type $ft_i32_to_i32) (param $x i32) (result i32)
    (local $b (ref $box))
    (local.set $b (struct.new $box (local.get $x)))
    (drop (suspend $yield (i32.const 0)))
    (struct.get $box 0 (local.get $b))
  )

  (func (export "gc_preserves_suspended_roots") (param $x i32) (result i32)
    (local $k (ref $ct_i32_to_i32))
    (block $on_yield (result i32 (ref $ct_i32_to_i32))
      (return (resume $ct_i32_to_i32 (on $yield $on_yield)
        (local.get $x)
        (cont.new $ct_i32_to_i32 (ref.func $gc_body))))
    )
    (local.set $k)
    (drop)
    (call $host_gc)
    (resume $ct_i32_to_i32 (i32.const 0) (local.get $k))
  )

  ;; 10. Covariant continuation subtyping
  (func $sub_fn_impl (type $sub_fn) (param $a anyref) (result (ref $box))
    (struct.new $box (i32.const 555))
  )

  (func (export "covariant_subtyping") (result i32)
    (local $sub (ref $sub_ct))
    (local $sup (ref $sup_ct))
    (local.set $sub (cont.new $sub_ct (ref.func $sub_fn_impl)))
    (local.set $sup (local.get $sub))
    (struct.get $box 0
      (ref.cast (ref $box)
        (resume $sup_ct (ref.null $box) (local.get $sup))))
  )

  ;; 11. Host function wrapped in cont.new
  (func (export "host_cont_resume") (param $a i32) (param $b i32) (result i32)
    (resume $ct_i32_i32_to_i32
      (local.get $a)
      (local.get $b)
      (cont.new $ct_i32_i32_to_i32 (ref.func $add_host)))
  )

  ;; 12. Trap cases
  (func (export "trap_null_cont") (result i32)
    (resume $ct_i32_to_i32 (i32.const 1) (ref.null $ct_i32_to_i32))
  )

  (func (export "trap_already_consumed") (result i32)
    (local $k (ref $ct_i32_to_i32))
    (local.set $k (cont.new $ct_i32_to_i32 (ref.func $add_one)))
    (drop (resume $ct_i32_to_i32 (i32.const 1) (local.get $k)))
    (resume $ct_i32_to_i32 (i32.const 2) (local.get $k))
  )

  (func (export "trap_unhandled_suspend") (result i32)
    (suspend $yield (i32.const 1))
  )

  (func (export "trap_unhandled_switch") (result i32)
    (switch $sw_ct $sw_tag (i32.const 1) (cont.new $sw_ct (ref.func $peer_a)))
    (drop)
  )
)
