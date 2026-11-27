(module
  (type $ft (func))
  (type $ct (cont $ft))
  (func (param (ref null $ct)) (result i32)
    (ref.test (ref $ct) (local.get 0))
  )
)
