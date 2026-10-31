# Fees are not refunded and merchants are not paid out

A refund returns the gross amount to the provider clearing account and takes all of it from merchant payable, while the platform keeps its fee. A full refund of a 10000 payment with a 300 fee therefore leaves merchant payable at -300.

The ledger allows that deficit instead of enforcing a nonnegative balance. Enforcing one would need a rule for what happens to a refund the merchant cannot cover, and with no payouts in scope there is no cash flow to settle it against. Payouts stay out because they add bank integration without adding a new failure mode to demonstrate.
