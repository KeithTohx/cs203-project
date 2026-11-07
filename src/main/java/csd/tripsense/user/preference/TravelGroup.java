package csd.tripsense.user.preference;

/**
 * TravelGroup:
 * Refers to how the user is travelling.
 *      SOLO - Travelling alone
 *      FAMILY - Travelling as a family
 *  
 * If TravelGroup=FAMILY, the user can be travelling with children or the elderly.
 * Hence travellingWithChildren or travellingWithElderly may be set to true.
 * 
 * If TravelGroup=SOLO, travellingWithChildren and travellingWithElderly can never be set to true.
 */
public enum TravelGroup { 
    SOLO, 
    FAMILY 
}
