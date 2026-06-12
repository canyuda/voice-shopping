## ADDED Requirements

### Requirement: UserBehaviorSink event listener
The system SHALL provide a `UserBehaviorSink` class in the business module that listens for Spring ApplicationEvent events and updates the `user_profile_dynamic` table in real time.

#### Scenario: onViewed event updates preferences
- **WHEN** an event indicating user viewed a product (with userId, category, brand) is received
- **THEN** the `category_prefs` for that category is incremented by the configured view weight (default 0.05), `brand_prefs` for that brand is incremented by the view weight, and a behavior entry is appended to `recent_behavior`

#### Scenario: onPurchased event updates preferences
- **WHEN** an event indicating user purchased a product (with userId, category, brand, amount) is received
- **THEN** the `category_prefs` for that category is incremented by the configured purchase weight (default 0.15), `brand_prefs` for that brand is incremented by the purchase weight, `purchase_count` is incremented by 1, `avg_order_amount` is recalculated, `last_purchase_at` is updated, and a behavior entry is appended to `recent_behavior`

#### Scenario: New category or brand gets initialized
- **WHEN** a view or purchase event references a category or brand not yet in the preferences map
- **THEN** the new key is added with the increment value as its initial score

### Requirement: Configurable weight values
The system SHALL accept configuration properties for `voice-shopping.behavior.view-weight` (default 0.05) and `voice-shopping.behavior.purchase-weight` (default 0.15).

#### Scenario: Custom weight configuration
- **WHEN** `voice-shopping.behavior.view-weight=0.1` is set
- **THEN** onViewed increments category_prefs and brand_prefs by 0.1 instead of the default 0.05

### Requirement: Cache eviction after behavior update
After successfully updating the dynamic profile in PG, `UserBehaviorSink` SHALL evict the UserProfileSnapshot cache for that userId, so subsequent loads reflect the updated data.

#### Scenario: Cache evicted after view event
- **WHEN** onViewed completes the PG update
- **THEN** the Redis key `vs:user:profile:{userId}` is deleted

### Requirement: recent_behavior bounded size
The `recent_behavior` JSONB array SHALL be capped at a configurable maximum size (default 50 entries), with oldest entries dropped when the limit is exceeded.

#### Scenario: Behavior list trimmed at capacity
- **WHEN** a new behavior entry is appended and recent_behavior already has 50 entries
- **THEN** the oldest entry is removed before appending, keeping the list at 50
